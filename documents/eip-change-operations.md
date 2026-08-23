# Changing content from a route

The `cms:` operations that write, and the rules that decide what each one
guarantees. For the session and transaction they run inside, see
`eip-session-lifecycle.md`.

---

## 1. Which operation writes what

| Operation | Writes |
|---|---|
| `cms:store` | A file's content, creating it and its parents when needed |
| `cms:setProperties` | A **set** of properties, taken from exchange headers or properties |
| `cms:setProperty` | **One** property, with a condition and an operation |
| `cms:setPropertyElement` | **One element** of a multi-valued property |
| `cms:createFolder` | A folder, optionally with mixins |
| `cms:move` | A node, to another path |

`setProperties` and `setProperty` are not two spellings of the same thing.
`setProperties` copies headers onto a node — `includes=commerce:*` and the whole
group lands. `setProperty` names one property and can say *under what
circumstances* to write it and *how* to combine the new value with the old one.

### `cms:setProperties` deletes on null

A header whose value is null **removes** the property. This is how a route clears
state it set earlier:

```xml
<setHeader name="commerce:errorMessage">
    <simple>${null}</simple>
</setHeader>
<toD uri="cms:setProperties?context=cmsContext&amp;path=${header.orderPath}&amp;includes=commerce:*"/>
```

There is no `removeProperty` operation because this already is one.

### `cms:setProperties` can read exchange properties

An `includes` entry names where it reads from. Bare, or written `@header.x`, it is
a header; written `@property.x` it is an exchange property — the same namespace
the output bindings write with `@property.x=result`, so a value a route parked
out of the header's way can still land on a node.

```
includes=commerce:orderId=@property.orderId   one property, from one exchange property
includes=@property.commerce:~                 all of them under that prefix, prefix stripped
```

`excludes` takes no prefix. It is matched against the name a source yielded, and
a header and an exchange property of the same name are the same name.

A wildcard under `@property.` sweeps Camel's own exchange properties along with
the route's. Name them, or give the route's own a prefix.

---

## 2. `cms:setProperty`

```
cms:setProperty?path=&name=&value=|valueFrom=&op=set|increment|max|min
               &expect=&expectMissing=&skipWhenBlank=&type=&retryOnConflict=
               &@header.applied=applied&@header.value=value
```

| Option | Default | Meaning |
|---|---|---|
| `path` | — | Required |
| `name` | — | Required. The property name |
| `value` / `valueFrom` | — | The value, or the name of the header holding it. One is required, except for `op=increment` where the step defaults to 1 |
| `op` | `set` | `set`, `increment`, `max`, `min` |
| `expect` | — | Write only when the current value is one of these (comma-separated) |
| `expectMissing` | `false` | Write only when the property is absent |
| `skipWhenBlank` | `false` | Do nothing when the value is null or blank |
| `type` | inferred | `String`, `Long`, `Double`, `Decimal`, `Boolean`, `Date` |
| `retryOnConflict` | `0` | Attempts after the first when another session wins the race (§4) |

Output bindings: `applied` (whether it wrote) and `value` (the value after
writing — how the result of an increment is read back).

### `expect` — a transition with its precondition on the route

```xml
<toD id="lane-claim"
     uri="cms:setProperty?path=${header.jobPath}&amp;name=commerce:job_status&amp;value=PROCESSING&amp;expect=READY&amp;@header.claimed=applied"/>
<filter id="lane-claimed">
    <simple>${header.claimed} == true</simple>
    ...
</filter>
```

The state the job has to be in, and whether it was, are both on the canvas. The
`applied` binding is the point: without branching on it the guard guards nothing,
because the node that did not write carries on exactly like the one that did.

**`expect` is not a compare-and-set.** It reads, compares, writes and commits,
and another session can interleave. Reading it as "atomic, therefore safe" is how
you get a race that only shows up under load.

| To guarantee | Use |
|---|---|
| The precondition of a transition is visible | `expect=` plus the `applied` binding |
| No other node is in the critical section | A **lock** around it — `cms:lock`, or a singleton route. Not `expect=` |
| Concurrent writes to one node do not lose | `retryOnConflict` (§4) |

`expect=${header.x}` where the header is unset resolves to the empty string.
Comparing a property against `""` is a condition that can never hold, so the node
would silently never write; it therefore means the same as `expectMissing=true` —
write only when the property is absent.

**The cost: `expect` cannot express "the property is present and empty."** That
reading is given to the absent case, so there is no spelling left for the empty
one. If you need it, branch on the value first — read it with
`cms:getProperties` and gate the write with a `<filter>` — rather than writing
`expect=` and finding out that it never fires.

**`expect` compares text.** It reads the property's string form and compares it
with the option's, so `expect=${header.x}` only ever matches when the value
read out of the repository and written back into a header renders identically.
That holds for `String`, `Long` and `Boolean`, and does not hold for `Date`: a
JCR `DATE` reads back as a `Calendar`, whose header form is
`java.util.GregorianCalendar[time=…]`. A moment that has to be guarded is
therefore stored as a `String` — which is the shape `transform:toInstant`
produces for exactly this reason (§5).

### `op=increment` — a counter as a node

```xml
<toD id="health-count"
     uri="cms:setProperty?path=${header.metricPath}&amp;name=commerce:count&amp;op=increment&amp;value=1&amp;retryOnConflict=6&amp;@header.newCount=value"/>
```

`max` and `min` take the same shape — a high-water mark, a last-write-wins
timestamp. All three are numeric: the current value and the step are read as
decimals and the result is written back as `Long` unless `type` says otherwise,
so a counter stays a counter. With no property yet, the step is the result.

---

## 3. `cms:setPropertyElement`

```
cms:setPropertyElement?path=&name=&op=add|remove&value=|valueFrom=&type=&retryOnConflict=
                       &@header.applied=applied
```

`cms:setProperties` can only replace a multi-valued property whole, so keeping a
set — the orders awaiting fulfilment, say — meant reading it, changing it and
writing it back inside a script. This does it as one node.

- `op=add` on an element already present reports `applied=false` and writes
  nothing, so the property never grows duplicates.
- `op=remove` on an element that is not there reports `applied=false`.
- With no property yet, `add` creates a multi-valued property of one element and
  `remove` reports `applied=false`.
- New elements take the type of the ones already there. `type` applies only when
  the property is being created, where there is nothing to match.

---

## 4. `retryOnConflict`

The repository's only concurrency signal is optimistic. A session that saves a
node another session has changed since it read it gets
`InvalidItemStateException`; there is no lock held in between. Absorbing that is
a read, a discard, a re-read and another attempt.

`retryOnConflict=n` does that loop: `n` retries after the first attempt, backing
off 20 ms, 40 ms, 80 ms … to a 500 ms ceiling. It is available on `cms:store`,
`cms:setProperties`, `cms:setProperty` and `cms:setPropertyElement`.

**When the attempts are used up it throws.** A counter that quietly stops
counting is worse than one that fails, because nothing downstream can tell "no
events" from "not recording".

**It is refused on a node that joined the route's session**, with a message
saying so. This is not only a restriction on retries — see the rule in
`eip-conventions.md` about what must never be inside a business transaction. A
counter that needs `retryOnConflict` is a counter that must not be in the
transaction anyway: an order that failed and rolled back still has to be counted
as failed. A joined node writes nothing durable — `cms:commit` does — so there is
no conflict here to retry, and the discard a retry performs would throw away
every other pending change in the transaction. Retry where the write happens:
either let the node own its session by keeping it out of the route's, or handle the
conflict on the route around `cms:commit`.

The default is `0`. Writing it where it is needed keeps the places that expect
contention visible on the route, rather than making every node quietly resilient
and none of them obviously so.

---

## 5. `transform:` — computing a value

The `cms:` operations write; `transform:` produces the values they write. Two
shapes:

| | Selects with | Writes to |
|---|---|---|
| In place | `targets=` (a filter: `commerce:*`, `@body`, `@property.x`) | the same place, same name |
| Producing a value | `of=` (named operands) | `@header.x=result`, `@body=result`, `@property.x=result` |

The difference in how a missing value is treated follows from that. `targets=` is
a **filter**, and a filter may legitimately match nothing, so a null target is a
no-op. `of=` **names** its operands, so a header that is not set is a wiring
mistake: `onNull` defaults to `fail`, and `skip` / `zero` / `empty` are how you
say otherwise. `transform:coalesce` is how you say "it might be absent, and here
is what to use instead".

### In place

| Operation | Options |
|---|---|
| `round` | `scale` (default 0, negative rounds to tens and up), `mode` (a `RoundingMode` name, default `HALF_UP`) |
| `trim` | — |
| `lowerCase` | `locale` (default `ROOT`) |
| `replace` | `search` (required), `replacement`, `regex`, `all` (default true) |
| `removeHeader` | `onlyWhenBlank` |
| `fromMap` | `from` (required), `prefix`, `suffix`, `targets` (default: every entry) |

`trim` uses `String.strip()`, so ideographic and other Unicode spaces go too. It
leaves an empty string rather than removing the target — whether a blank value
should exist is a separate decision, which is what `removeHeader?onlyWhenBlank`
is for.

`lowerCase` defaults to `Locale.ROOT`, not the platform's: under a Turkish locale
`"I".toLowerCase()` is `"ı"`, and an email address normalised that way stops
matching itself. Case-folding an identifier is not locale-dependent, whatever the
numeric parsers do with theirs.

`fromMap` is the way to work on one entry of a map — a row of a query result,
say, which is what `<split>` hands each iteration. Nothing else in the vocabulary
can reach inside one, which is why a route holding such a row used to go to
Groovy to look at a single field of it:

```xml
<toD id="sla-task-fields" uri="transform:fromMap?from=@body&amp;prefix=slaTask_"/>
```

`from` **names** its source, so an absent one or one that is not a map fails —
unlike `targets=`, which filters and may match nothing. `targets=` on `fromMap`
selects which entries to spread and defaults to all of them: an absent filter
there means "spread what I gave you", not "spread nothing".

### Producing a value

| Operation | Options |
|---|---|
| `add` / `subtract` / `multiply` | `of` (2+), `onNull` |
| `divide` | `of` (2+), `scale` (default 10), `mode`, `onZero` (`fail` / `null` / `zero`), `onNull` |
| `concat` | `of` (1+), `separator`, `skipBlank`, `onNull` |
| `coalesce` | `of` (2+), `blankIsNull` (default true) |
| `toInstant` | `of` (1: a moment, or `now`), `onNull` |
| `dateAdd` | `of` (1: a moment, or `now`), `amount`, `unit`, `onNull` |
| `lookup` | `of` (1: a map or list), `key`, `defaultValue`, `onNull` |

An operand is a bare header name, `'a quoted literal'`, an unquoted number,
`@body`, or `@property.x`. **`@header.x` is not a way to read one**: in every
component `@header.foo=bar` means *write to* header foo, and a URI where the same
notation points both ways cannot be read at a glance.

### Numbers keep their type

The arithmetic runs in `BigDecimal` throughout, and the result is narrowed back
to a type the operands decide:

| | Result |
|---|---|
| any operand is `BigDecimal` or `BigInteger` | `BigDecimal` |
| otherwise any `Double` | `Double` |
| otherwise any `Float` | `Float` |
| otherwise any `Long` | `Long` |
| otherwise | `Integer` |

Java's own promotion, with `BigDecimal` added on top so that money is not demoted
to a floating-point approximation by being added to something. A sum of `Long`s
is a `Long`; `divide?of=a,b` on two `Long`s is `3` for `7/2`, as in Java.

**A type change is a node.** `transform:toDecimal` in front, visible on the
canvas — not a component quietly turning a `Long` into a `BigDecimal` and
breaking the step that expected the old type.

Two consequences worth knowing:

- **A `String` is refused**, with a message naming `toLong` / `toDecimal`. The
  conversion operations parse leniently — `NumberFormat` reads as far as it can
  and discards the rest, so `"12abc"` is 12 — which is right for a conversion and
  an accident waiting to happen in a multiplication.
- **Overflow throws**, unlike Java, which wraps. A count of orders that silently
  becomes negative is worse than one that fails. This is the one place these
  operations deliberately disagree with Java arithmetic.

A literal takes the result type rather than setting it: `add?of=count,1` on an
`Integer` count stays an `Integer`. A literal with a fraction against an integral
result is refused rather than truncated — `add?of=intCount,0.5` would otherwise
be a node that does nothing and says nothing.

### A moment is ISO-8601 UTC text

`toInstant` and `dateAdd` both produce `2026-07-29T12:00:00.000Z` — fixed width,
UTC, three fractional digits, always. Not a `Date`, and the difference is not a
detail: a moment on a route has to cross four boundaries and text is the only
thing that crosses all four.

| Crossing | What a `Date` does there |
|---|---|
| A `jexl` comparison | Against another `Date`, correct. Against a `Calendar`, throws. Against a `String`, `toString()` — `"Wed Jul 29 …"` sorts after every digit, so the comparison is true for **every** pair of instants |
| A property | A JCR `DATE` reads back as a `Calendar`, which is neither of the two a `Date` compares with |
| `expect=` | The guard compares text, so the value has to round-trip through a header identically. `java.util.GregorianCalendar[time=…]` never matches |
| An operator's eyes | A repository browser, a log line, a diff |

The middle two are why a cooldown stamp is a `String` property. The first is why
the fraction is always written: `DateTimeFormatter.ISO_INSTANT` drops a zero
fraction, `.` sorts before `Z`, and two spellings of one instant then compare as
different and in the wrong order.

`toInstant` is the conversion node for moments, the same way `toLong` is the one
for numbers — a type change is a node. It takes a `Date`, a `Calendar`, epoch
milliseconds, an ISO string in any of the accepted shapes, or `now`, and it is
idempotent, so it is safe in front of a value that may already have been through
it.

`jexl` refuses to order a moment against text, or text against a number. The
message names the conversion to put in front. That rule used to be a bullet in
`eip-conventions.md` and nothing else; the first route that needed a comparison
is what showed that a bullet is not a rule.

### `dateAdd` measures elapsed time, not calendar days

`unit=days` is 86,400,000 ms. No daylight-saving adjustment happens, so a cutoff
computed across a DST boundary is an hour away from what a calendar would say.
That is right for "more than N minutes since it was created", which is what a
monitor asks, and wrong for "the same time yesterday". A calendar unit would be a
separate one.

---

## 6. `cms:loadYaml` — the settings a route depends on

```
cms:loadYaml?path=&optional=&conflictBehavior=
             &@header.x=<a path into the document>
```

```xml
<toD id="sla-config"
     uri="cms:loadYaml?path=/etc/commerce/config/sla.yml&amp;optional=escalation.priority&amp;@header.slaOpenMinutes=open.minutes&amp;@header.slaKeys=processKeys&amp;@header.slaEscalationPriority=escalation.priority"/>
```

The source name of each binding is a **dotted path into the parsed document** —
`open.minutes`, `items[0].name` — or the reserved name `document` for the whole
map. So which settings a route depends on is the endpoint, rather than the first
twenty lines of a script.

### Not a data format

`<unmarshal><yaml/></unmarshal>` would have been cheaper and answers a different
question. A data format converts the **body**, and on a webhook route the body is
the payload being processed: reading a config file that way means stashing the
payload, replacing it, picking a value out of it, and putting the payload back —
four more nodes carrying no more information than one. And what the route
depended on would still only be legible by finding `${body[overdue][minutes]}`
somewhere downstream.

It would also not have run. **This deployment loads no Camel data format at
all** — the jars carry components and languages and no entry under
`META-INF/services/org/apache/camel/dataformat`, so `<marshal>` and
`<unmarshal>` fail at route startup whichever format they name. The Modeler used
to offer six of them; it offers none now, and `scripts/check-dataformats.py`
refuses a palette entry for a format nothing declares, so putting one back means
deploying it first.

The two elements are still read and still written, so a route that has one is
not quietly gutted by being opened. Nothing can create one.

### A missing key is an error

Binding null for a key nobody wrote is how a threshold silently becomes zero and
a monitor silently stops monitoring. `optional=` lists the paths that may
legitimately be absent, which puts "this setting has a default" on the route
instead of in the reader's memory.

The consequence is worth stating plainly: **a config file has to be complete.**
Deleting a line from it is a deployment failure rather than a behaviour change,
and switching something off is done by setting its value, not by removing it.

**There is no `default=` option, and that is the design rather than a gap.** A
default is a decision — "unclaimed for sixty minutes is too long" — and the whole
point of reading settings this way is that such decisions are legible. Written
here it would sit in a URI, in a repository nobody deploys, describing an
application it knows nothing about; written in the config file it is next to the
comment explaining it, in the file an operator edits. So the file carries the
value and this refuses to invent one.

The scripts this replaced all had defaults, in the form `cfg.minutes ?: 60`. That
is what made a hand-edited file that had lost a line behave differently instead
of failing — the reading that produced a monitor with a threshold of zero and
nothing to say about it.

### A missing file is `conflictBehavior`

The same vocabulary as everywhere else (§8): `FAIL` (the default) throws;
`IGNORE` and `WARN` bind nothing, so the route branches on the headers being
unset. That is how "this feature is off because it was never configured" is
written as a branch rather than as an early return inside a script.

### Types are not converted

Whatever the YAML parser produced is what is bound — `String`, `Long`,
`Boolean`, `List`, `Map`. A list is bound as a list, which `<split>` iterates
directly. A type change is a node: `transform:toLong`, `transform:toInstant`.

---

## 7. `cms:load?decompress=`

Whether a stored export is compressed is a property of the file, not of the
route: the same bulk result arrives gzipped or not depending on its size.

| Mode | Behaviour |
|---|---|
| `auto` (default) | Unwrap when the content starts with the gzip magic bytes |
| `gzip` | Insist on gzip; fail if it is not |
| `none` | Pass the content through |

`cms:loadAsString` takes it too, alongside `encoding` (default UTF-8).

---

## 8. Conflicts with what is already there

`conflictBehavior=FAIL|IGNORE|WARN` is the single vocabulary for "the target is
already in the state you asked for, or is not in a state you can act on". It is
on `cms:createFolder`, `cms:unlock` and the version operations. There is no
second vocabulary — no `onError`, no `missingBehavior` — because two ways to say
what to do about a failure on one node is one way too many.

---

## 9. Finding the work: `cms:query`, `cms:list`, `cms:remove`

The routes whose logic drifted furthest into Groovy were the ones that had to
**find** their work — prune what is past the retention window, redact everything
belonging to a customer, recompute the facts that are stale. A `<split>` can
iterate a list, but nothing on a route could produce one, so *what* was being
iterated stayed in a script and the loop on the canvas was decorative.

```xml
<setHeader id="prune-query" name="pruneQuery">
    <simple>/jcr:root/content/commerce/events//element(*, nt:file)[@commerce:received_at &lt;= $cutoff]</simple>
</setHeader>
<toD id="prune-find"
     uri="cms:query?context=cmsContext&amp;statement=pruneQuery&amp;bind.cutoff=${header.cutoff}&amp;bindType.cutoff=date&amp;limit=5000&amp;@header.stalePaths=paths&amp;@header.staleTotal=count&amp;@header.staleTruncated=hasMore"/>
```

### The statement is a header

XPath is full of `< > [ ] ( )`. In a URI it would be escaped twice — once for
XML, once for the query string — and unreadable on a canvas. `statement=` names
a header; the statement is a `<setHeader>`, and it is one visible node.

### Values go in as bind variables, or not at all

`$name` in the statement, `bind.name=` beside it, `bindType.name=` when it is
not text. **A route author never concatenates a value into a statement**, and
there is no option that would let them.

That is a correctness change rather than a visibility one. JCR defines bind
variables and this repository's query engine refuses all of them, so every
caller that needed a value built the string itself — **seven files carry their
own escaping and one of them handles only the apostrophe.**

| `bindType` | Written as |
|---|---|
| `string` (default) | a quoted literal, with `'` doubled |
| `long` / `double` / `decimal` | a number, or an error |
| `boolean` | `true()` / `false()` |
| `date` | `xs:dateTime('…')`, from any shape `transform:` produces |
| `path` | **bare**, for the subtree being searched |
| `name` | a quoted JCR name |

`path` is bare on purpose: every measured use of a path in a statement is
`/jcr:root$root//element(…)`, where a quoted one searches nothing and says
nothing. A path used as a value to compare against is a `string`.

An unbound `$name` is refused rather than treated as empty — **an empty
predicate matches everything, which on a delete sweep is the worst available
default.** A `bind.` the statement does not use is refused too, because that is
what a misspelling looks like from the other side.

### `limit` is required, and truncation is visible

Not because of the old implicit hundred — that is gone — but because a search
with no ceiling is a cost nobody wrote down, on a route that may one day run
against a store a thousand times bigger.

`count` is how many matched; `paths` is what came back; `hasMore` says the
answer was cut short. It is the same distinction as `bpm:` paging (§8 of the
conventions), for the same reason: **a sweep that saw part of its work must be
able to say so, or it looks exactly like one that saw all of it.**

### `cms:list` is not a small `cms:query`

The search index is updated asynchronously, so a query can miss a node written
moments ago by the same route. `cms:list` reads children through the repository
and sees what is there now. A route that writes and then walks what it wrote
needs `list`; a route that searches a store nobody just touched wants `query`.

`depth` defaults to 1 and stops at 4. Deeper than that is a walk of the
repository rather than a list, and it belongs in `query`, where the statement
says what is being looked for.

### `cms:remove` closes the loop

A route that can find what is stale and cannot delete it has moved half a loop
onto the canvas and left the half that matters in Groovy — which is why this
arrives with `query` rather than with the other change operations.

It removes the subtree, and it leaves the deletion in the session: it lands at
`cms:commit` and is discarded by `cms:rollback`. **A deletion belongs to the
transaction that decided on it.** `conflictBehavior` defaults to `IGNORE`,
because a node another sweep already took is not this one's failure.

### What was specified and not built

**`offset`.** Query-level offset is used in exactly zero of the twenty searches
in the application. The screens page by fetching everything and counting, which
is a screen problem; no route wants it. An option that is accepted and ignored
is the failure the option catalogue exists to remove, so it is not accepted.

**Ordering.** Six searches order their results and all six are screens. When a
route wants ordering the option can be added — with the same measurement behind
it.

**The JCR bind-variable contract.** `Query.bindValue` still throws. The
substitution happens one level up, in the component, so what a route author gets
is the whole guarantee and what a direct JCR caller gets is unchanged. The
engine-level contract stays unimplemented and stays recorded as such.

### `limit` on `cms:list` arrived late

`cms:list` shipped without one, and the first route that needed it showed why
that was wrong: it lists a folder of pending markers that holds a handful of
nodes most of the time and **the entire order history for the hour after a
backfill seeds it**. Same rule as `cms:query`, same `count` and `hasMore`, for
the same reason — a listing with no ceiling is a cost nobody wrote down.

Worth recording rather than quietly fixing: the operation was specified,
implemented, catalogued and documented without the bound, and none of that
noticed. **A route using it did, on the first try.**
