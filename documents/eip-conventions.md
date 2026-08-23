# EIP route conventions

Rules that hold across routes, with the failure each one prevents. The
mechanics live elsewhere: `eip-session-lifecycle.md` for the session and its
transaction, `eip-change-operations.md` for the operations that write.

A rule is here because getting it wrong fails **quietly**. Preferences belong in
review, not in a document that reads like a contract.

---

## 1. What must never be inside a business transaction

**Anything that records that the business failed must not be rolled back with
it.**

| Keep outside | Because |
|---|---|
| Metrics and health counters | An order that failed and rolled back still has to be counted as failed. Inside the transaction, the record of the failure is discarded along with the failure |
| Audit logs | Same shape. "We attempted this and it did not work" is a fact about the attempt, not part of it |
| The receipt of an inbound payload | It is the *input* to the transaction. It is also what an error handler stamps, and after a rollback there would be nothing there |
| Notifications, and anything else that leaves the process | A rollback cannot recall a message that has already been sent, so sending inside the transaction means sending things that did not happen |

In practice: those nodes **reach no session**, so each owns its session and
commits immediately. Note what that does *not* mean. The `context` option
defaults to the `cmsContext` header, so leaving the option off a stretch of route
where a session is published under that name joins it rather than avoiding it.
What keeps a node out is position: it goes where the route has not opened a
session, or where the session was published under a name it does not read.

Where they sit relative to `cms:commit` is then a matter of ordering, not of
transactionality — a side effect that must follow a durable record goes after the
commit, and a receipt that must survive a rollback goes before the transaction
opens.

Nothing enforces this. A `cms:` node inside a `<doTry>` whose `cms:login`
published under a name other than the default looks up the default name, finds
nothing, and owns its session — which is right for the nodes in the table above
and wrong for the rest, and the difference is one an author has to see.

### The same rule, from the other direction

`retryOnConflict` is refused on a node that joined the route's session. That
looks like a restriction on retries; it is the same rule again. A joined node
writes nothing durable, so there is no conflict to retry — and the discard a
retry performs would throw away the rest of the transaction. A counter that
needs `retryOnConflict` is a counter that must not be in the transaction anyway.

---

## 2. Retries belong in one place

Two error handlers that both retry **multiply**. A route that retries three
times, called by a core that retries twice, attempts the work twelve times — a
number nobody chose, written down nowhere, and readable only by finding both
files and multiplying.

Pick the layer that owns the decision — usually the one that received the work
and knows what a duplicate costs — and set every other layer to
`maximumRedeliveries="0"`. The inner routes fail; the outer one decides how
often to try again.

**Test: how many times an event is attempted has to be answerable from one
file.**

---

## 3. A failure must not be reported as a success

`<doCatch>` clears the exception. A block that catches, rolls back and falls out
of the catch finishes with no exception at all: message history records the
exchange as completed, the caller's `redeliveryPolicy` never runs, and nothing
was written. End such a block with `<throwException>`.

The mirror of this: a route dispatched by something that records the outcome
must not swallow its own failure. `handled="true"` on the inner route means the
caller sees a normal return and records success, and any recovery keyed on the
recorded status — an automatic replay, a retry sweep — can never see the events
that needed it.

There is one legitimate exception, and it has a precondition: work that runs
**after the durability point** may catch its failure and not re-throw, because
the record is already committed and re-throwing would send an
already-processed entity down the error path. It must still be recorded
somewhere an operator looks — its own health bucket, its own log line. Catching
it and doing nothing is not this exception.

---

## 4. A guard has to be branched on

An operation that reports whether it acted — `cms:lock` with `locked`,
`cms:setProperty` with `applied` — is a guard only if something reads the
answer. The node that lost the race carries on into the critical section exactly
like the node that won, unless a `<filter>` or `<choice>` says otherwise.
Nothing checks this, so it is a review rule.

---

## 5. Every node has an `id`

Message history names a step by its `id`. A failure you cannot name is a failure
you cannot find, and a route of anonymous nodes reports its error against a
generated identifier that means nothing an hour later — one that also shifts the
moment a node is inserted above it.

Nothing refuses an unnamed node, so this is a review rule — but it is the one
worth being strict about, because what it costs is the ability to read a failure
report at all.

---

## 6. A rule nothing enforces is not a rule

**Do not leave the enforcement of a rule to a tool that does not exist yet.**

A prohibition that lives only in a document does nothing when it is broken —
and *nothing happening* is the state this entire document exists to remove. A
route that violates a written-down rule and deploys anyway is indistinguishable
from one that follows it, right up until the day it matters.

So when something is forbidden, decide at the same time **where it fails**:
at startup, when the route is added, when the build runs. If there is nowhere to
fail it, write it as advice rather than as a rule, and say which it is.

The worked example is the `jexl` language. The specification banned dot notation
and assigned the check to a linter that was not written. Under the permissions
that ship, a refused method call does not throw — it evaluates to `null`, so
`header['email'].toLowerCase() == 'a@b.com'` was a predicate that was quietly
always false. The ban moved into the language, which refuses it when the route
is added; the linter can still come later and say it more kindly. An
unimplemented tool cannot be the only thing standing between an author and a
condition that never matches.

### Turning a rule on before every route obeys it

A rule written for routes that already break it in two hundred places cannot
simply be switched on: it would refuse everything, so it would be reverted, so
it would never be on. The usual alternative — "we will enable it once every
route is fixed" — is worse, because until that day the rule does nothing at all,
including for the route somebody writes tomorrow.

So a route named the rules it had not been brought up to yet, and the platform
enforced them everywhere else from the day each was written:

```xml
<route id="shopify-order-paid">
    <routeProperty key="mi:conventions.pending" value="unnamed-nodes"/>
```

The work that was left was named, in the file that owed it, one line per piece
of work, deleted as part of fixing it — so the count could only go down. It
started at 74 across 37 routes and reached zero.

**The mechanism is gone with it.** Keeping it past zero would leave a way to
declare an exemption with nothing left to be exempt from, which is the silent
thing these rules exist to stop. `mi:conventions.pending` is no longer read, no
longer accepted, and no longer documented anywhere but here.

### What the staged rules found on the way down

This is the argument for staging rather than waiting. All three were written for
routes that broke them, and all three turned up something while being obeyed:

| Rule | What it found |
|---|---|
| `undescribed-parts` | **An edge nothing drew.** A part started another route from inside a script, so the canvas showed a flow that stopped where the real one carried on. |
| `unnamed-nodes` | **Decisions with no name.** "What counts as money coming in" existed only inside three nested predicates until they had to be called something. |
| `ordering-in-simple` | **A comparison between unlike things.** A stored instant against a formatted date, which was true for every possible pair of values. |

None of these would have been found by waiting until every route already obeyed
the rule, because the way each was found was by somebody having to obey it.

## 7. Values are nodes; conditions are predicates

A route has two kinds of expression, and keeping them apart is what makes a
canvas readable.

| | Where | What it may do |
|---|---|---|
| `transform:` | a node on the flow | produce a value: arithmetic, text, dates, defaults |
| `jexl` | inside `<when>` / `<filter>` | decide yes or no, from values already computed |

### Which predicate language

**Equality and existence in `<simple>`. Ordering, grouping and regular
expressions in `<language language="jexl">`.**

An ordering comparison in `<simple>` is refused when the route is added. Simple's
`<` and `>` coerce one side to the other's type and, when that fails, compare
`String.valueOf` of each — an ISO instant against a `java.util.Date` becomes
`"2026-07-29T…"` against `"Wed Jul 29 …"`, which is an answer, always the same
answer, and never the question that was asked. `jexl` refuses that comparison
instead and names the conversion to put in front — see "Compare like with like"
below.

`==` and `!=` stay in Simple: they are the majority of predicates, they read
better, and `${header.x} == 'paid'` has nowhere to go wrong. Reach for `jexl`
when the condition needs parentheses, a header name containing a colon, `=~`, or
an ordering.

**A predicate does not calculate.** `header['a'] + header['b'] > 100` hides an
addition inside a condition; the addition is a node, and then the condition reads
the result. The same goes for `header['email'].toLowerCase() == …` — that is
`transform:lowerCase` in front, and a comparison after.

The language enforces the part it can: dot notation is refused when the route is
added, which covers both a method call and a property dereference. The rest is a
convention, and the reason for it is not tidiness — a value computed inside a
predicate is a value nobody can see on the canvas, log, or point at in a review.

### Writing a `jexl` predicate

```xml
<when>
    <language language="jexl">(header['txn_kind'] == 'sale' || header['txn_kind'] == 'capture')
        &amp;&amp; (empty(header['txn_status']) || header['txn_status'] == 'success')</language>
</when>
```

- **`header['name']`, never `header.name`.** Bracket notation reads a name
  containing a colon — `header['commerce:status']` — which Simple cannot do at
  all, and an absent key is null rather than an error.
- **`empty(x)`** is true for null, an empty string, and an empty collection or
  map. It is the usual way to say "not set".
- **`=~` is both** membership and pattern: `header['status'] =~ ['paid','authorized']`
  against a list literal, `header['sku'] =~ '^SKU-\d+$'` against a string.
- **Compare like with like — enforced.** `<`, `<=`, `>` and `>=` refuse two
  operands of different kinds: text against a moment, text against a number.
  Numeric widths are one kind, because JEXL promotes across them and
  `Long(1) < Integer(2)` is the comparison it looks like. Put the conversion
  the message names in front — `transform:toInstant`, `transform:toLong`. This
  was advice until a route needed it: `lastAlertAt < cutoff`, a stored ISO
  instant against a `Date`, compared `"2026-07-29T…"` with `"Wed Jul 29 …"` and
  was true for every possible pair of instants. Equality is left alone —
  `header['x'] == null` compares unlike kinds on purpose.
- **No `?:`.** A branch is a `<choice>`; an expression that branches is a branch
  nobody can see.

---

## 8. A page has to be a page of something

Reading a list from `bpm:` has three options that only make sense together.

```xml
<toD id="sla-query"
     uri="bpm:queryTasks?processDefinitionKey=${header.slaKey}&amp;active=true&amp;orderBy=createTime&amp;order=asc&amp;maxResults=500&amp;@header.slaTasks=tasks&amp;@header.slaTotal=total"/>
```

**`maxResults` needs an `orderBy`.** "The first 500 of these" is not a question
with a stable answer unless something says which 500: run it twice and the rows
may differ, so the task that gets escalated today and the one that is missed
tomorrow are both arbitrary. Nothing enforces the pair any more — the option
catalogue that did was removed — so writing `maxResults` alone now produces an
arbitrary page rather than an error.

**Ordering and paging are the engine's.** `orderBy` becomes `orderByXxx().asc()`
and `maxResults` becomes `listPage(...)`; nothing sorts or slices after the
fact. Sorting a materialised page would sort the rows that happened to arrive —
the first N of an arbitrary order, re-sorted to look deliberate — and slicing one
still fetches everything, which is the cost the option exists to avoid.

**`orderBy` and `order` are written literally.** They name one of a fixed set of
words, so `orderBy=${header.sortColumn}` is refused: there is nothing to check
until a message arrives, and a sort order chosen by a header is a sort nobody
reading the route can see. `maxResults` is data and may come from configuration.

**`total` is how many matched; `count` is how many came back.** With a page they
answer different questions, and a monitor needs the first one so that it can say
when it did not see everything:

```xml
<filter id="sla-truncated">
    <language language="jexl">header['slaTotal'] &gt; 500</language>
    <log id="sla-truncated-log" loggingLevel="WARN"
         message="SLA scan truncated for ${header.slaKey}: ${header.slaTotal} open tasks"/>
</filter>
```

Without that branch a capped scan is indistinguishable from a complete one, which
is the same failure as a counter that stops counting: the graph reads lower and
nothing says why. `total` costs a second query and is only issued when something
binds it; `count` means exactly what it always meant.

**Acting on what a query found is nodes too.** `bpm:setTaskPriority` and
`bpm:addCandidateGroup` / `bpm:removeCandidateGroup` are the engine-side half of
an escalation — raise a stalled task so it sorts to the top of the list people
read, and put it in front of a second group. They exist because "escalate" was
otherwise a priority number inside a scanner script, unreadable from the flow
that decided to escalate.

## 9. A loop has to be able to say how many

A route that iterates has to be able to report what it did. Before the loops
became nodes that number was an `int` inside the Groovy part and a log line at
the end of it, and turning the loop into a `<split>` pushed the answer straight
back into Groovy — which was the one thing the conversion was supposed to
remove.

`<split>` folds its items with an **aggregation strategy**, named as an
attribute:

```xml
<split id="sla-per-task" aggregationStrategy="#commerceTally">
```

**The strategy is a script the application owns.** `#commerceTally` resolves to
`/etc/eip/aggregators/commerce/commerceTally.groovy`, alongside
`/etc/eip/routes/commerce/`: the two halves are deployed by uploading a file and
withdrawn by deleting a folder, and adding a column to a summary line does not
need a platform release. The platform supplies the adapter and no arithmetic at
all — what is worth counting is the application's business.

The name is flat and unique across the tree. A folder is where a script is
deployed, not part of what it is called, and two files with the same base name
are an error naming both paths rather than a route whose behaviour depends on
directory order.

**A strategy can fold headers and can do nothing else.** It runs against a bare
script context with six names in scope — `sub` (the item's headers, copied),
`acc` (the accumulator's headers, writable), `index`, `size`, `last`, `log` —
and nothing more. No repository session, no process API, no integration API: not
withheld by a rule, absent from the context. That matters because
`aggregationStrategy="#x"` declares no inputs or outputs the way a `cms:` part
does, so the channel is kept narrow enough not to need declaring.

**What leaves the split is the exchange that entered it.** The adapter
accumulates onto the parent and returns the parent, so its body and headers
survive with the totals added. Returning the last item instead — the obvious
implementation — would silently replace both.

### The contract a route has to keep

**An item reports its own contribution, never a running total.** Sub-exchanges
inherit the parent's headers, so a node that read the inherited value and added
to it would be counted twice: once by itself and once by the strategy.

**Every tally is set on every path, not only where it is earned.** The inherited
value is what stays otherwise. The shape that keeps this readable is to set them
all to their zero at the top of the split body and overwrite the ones a branch
earns:

```xml
<split id="sla-per-task" aggregationStrategy="#commerceTally">
    <simple>${header.slaTasks}</simple>
    <setHeader id="sla-tally-examined" name="tally_slaExamined"><constant>1</constant></setHeader>
    <setHeader id="sla-tally-alerted" name="tally_slaAlerted"><constant>0</constant></setHeader>
    ...
    <filter id="sla-armed">
        <language language="jexl">header['slaArmed'] == true</language>
        <setHeader id="sla-tally-alerted-yes" name="tally_slaAlerted"><constant>1</constant></setHeader>
```

A `<choice>` that sets a tally in one branch and leaves the inherited value in
another is the failure this shape exists to prevent, and it is invisible until
the numbers are compared against something that already knows the answer.

**Seed the accumulator before the split.** An empty split never calls the
strategy at all, so without a seed a scan that examined nothing carries no
numbers rather than zeroes — and a summary of blanks reads exactly like a scan
that did not run.

**In a split inside a split, the outer body's own contribution goes after the
inner split.** Anything set before it is inherited by every item of the inner
split and folded back once per item. Written afterwards it is folded once, by
the outer strategy, for the thing it belongs to.

### The rest of it is ordering

**Do not write `parallelAggregate="true"` together with a strategy.** A
strategy reads and writes one accumulator; two threads folding into it at once
lose an item, and the total comes out low some of the time and right the rest.
The option is left alone where there is no strategy, because then there is no
accumulator and nothing to lose — a rule that refuses a spelling rather than a
mistake gets removed instead of followed (§6).

**Header names say what happens to them.** `tally_*` is summed; the prefix is
the application's to choose, and choosing one means "this is added up" is
readable from the canvas rather than from the strategy.

**The summary is a node.** The line the Groovy part used to print is a `<log>`
after the split, reading the headers the fold left behind:

```xml
<log id="sla-summary" loggingLevel="INFO"
     message="task SLA scan: ${header.tally_slaKeys} definition(s), ${header.tally_slaExamined} task(s) examined, ${header.tally_slaBreached} breach(es), ${header.tally_slaAlerted} alert(s) sent"/>
```

A scan whose examined count drops to zero has stopped working. Per-item logs
never say that: they say a great deal about the two tasks that breached and
nothing about the four hundred that were read to find them.

## 10. An option that is not read is not an option

`cms:setProperties?pth=/a/b` used to behave exactly like a node with no path at
all. The components read their URI parameters by hand and then emptied the map,
which is what suppressed the check Camel would otherwise have made, so a
misspelling was not an error — it was a permanent silent no-op, and on a `<toD>`
whose URI is built per message, "nothing says so" could last for months.

**Every option each operation accepts is declared, and anything else is
refused.** The declaration is derived from what each producer actually reads, so
it is a statement about the code rather than about the documentation. An
operation nobody has written down yet is left unchecked, which means adding one
can only ever tighten the check.

It is applied as the endpoint is built: for `<to>` at startup, and for `<toD>`
once per message.

That timing is worth knowing when reading a mistake. A `<toD>` on a route that
only runs during a nightly sweep holds its bad option name until the sweep runs,
and then reports it attached to a message rather than to the edit that caused it.
A value carrying `${…}` cannot be checked before a message arrives at all, so
that part could never have been earlier.

**Some options carry a word rather than data**, and those are checked by value
too. `order` is `asc` or `desc`; `ascending` is refused. `orderBy` names a column
of *that* operation, so `dueDate` is a task's and not an incident's. And
`maxResults` is refused without `orderBy`, because the first N of an unordered
result is not a repeatable question (§8).

**An option with a vocabulary may not be written as an expression.** There is
nothing to check in `orderBy=${header.sortColumn}` until a message arrives, and a
sort column chosen by a header is a sort nobody reading the route can see —
the same objection, arriving earlier. `maxResults` is data and may come from
configuration.

### The same list, while the URI is being typed

The Modeler shows what the operation accepts under the URI field: what is
written, with anything that would be refused marked and why; and what else is
available, as names that can be added. Options with a vocabulary offer their
words.

It serves the platform's own declaration rather than a copy shipped with the
editor. A generated file would be a second list, and a form offering an option
the deployment refuses is precisely the failure the catalogue exists to remove.

Two things it deliberately does **not** do. It never rewrites a URI — the field
stays free text, because an editor that reformats what you typed is an editor
you stop trusting with the case it has not been taught. And it says nothing at
all about a scheme it does not catalogue, an operation chosen per message, or an
operation the catalogue has not got: **silence there means "no opinion", not
"nothing is allowed"**, which is the same rule the platform follows.

The failure worth guarding is the false alarm. A panel that marks `@header.x` or
`bpm:`'s `var.orderId` as unknown teaches an author to ignore it, and then it is
worth nothing on the day it is right — so the reading is checked against every
endpoint of every route that deploys, where it must find nothing, and against a
handful of URIs written to be wrong, where it must find each one.

## 11. A part says what it needs and what it produces

A `cms:` node names a script and a list of headers to hand it:

```xml
<toD id="health-bucket"
     uri="cms:/etc/commerce/scripts/health/bucket.groovy?inputs=health_name,health_metric&amp;outputs=healthBucket"/>
```

Nothing in that line says whether the script wants those headers. A misspelt
`inputs=` binds nothing, the script reads null, and **null is a value rather
than an error** — so it travels, and the failure surfaces somewhere else as a
missing field.

So a part declares its contract, in a comment block at the top of the file, and
the platform compares the two when the route is added:

```groovy
// Name the bucket an outcome is counted under.
//
// @part
//   verb: derive
//   inputs:
//     health_name    String  optional  outcome bucket (a topic, an API call)
//     health_metric  String  optional  plain counter
//   outputs:
//     healthBucket   String  the node name, or null when neither input is set
```

| Field | Means |
|---|---|
| `verb` | the one thing this part does |
| `inputs` | `name  Type  [required]  description` — the script's own name for the value |
| `outputs` | `name  Type  description` — likewise |
| `external` | the outside system it calls, if any |
| `reads` / `writes` | repository paths it touches |
| `sends` | endpoints it sends to from inside |

**In the comment, not beside it.** These applications are deployed by uploading
a file; a contract in a second file is a contract that gets left behind.

### What is refused

A binding the part does not declare, and a required input the node does not
bind — the same mistake from each side. An output taken that the part does not
declare producing.

**A destination the part sends to and does not declare.**
`MessageSender.setEndpointURI("direct:…")` starts another route from inside a
script, so the canvas shows a flow that stops where the real one carries on.
`sends:` is what makes that edge visible; undeclared, it is an edge nothing
draws.

**A destination the part computes.** `setEndpointURI(where)` cannot be declared,
so it cannot be shown — by the canvas, by the descriptor, by anything. A part
with a descriptor may not have one; write the destination as a literal.

### `external:` is why the vendor client stays in one piece

A Shopify client is one technique — envelopes, `userErrors`, cost and throttle,
bulk operations — and taking it apart into nodes would produce a canvas full of
HTTP mechanics and no business. So it stays a class. **`external:` is then the
only place "this is where we leave the building" appears anywhere a reader
looks**, which is the whole reason the generic `http:` component was not built.

### What is not checked

**"One part, one verb" is not machine-checkable and is not attempted.** `verb`
records the claim so a reader and a reviewer can see it; whether the code keeps
it is a judgement. A check that guessed would either pass everything or refuse
correct parts, and §6 says what happens to a rule that refuses the correct
spelling.

The specification also wanted a warning when a node calling an `external` part
has no `id`. It is not here: §5 already requires an id on every node, and a
second rule saying so for some of them would only ever fire where the first one
already had.

### Turning it on before every part has one

The same mechanism as §6, with one difference: **the exemption names the part.**

```xml
<routeProperty key="mi:conventions.pending"
               value="undescribed-parts:/etc/commerce/scripts/shopify/screenOrder.groovy"/>
```

`undescribed-parts` on its own is refused. It would cover every part the route
calls — including one added tomorrow — so the exemption would widen as the route
grew, which is the failure the staging mechanism exists to prevent.

Naming the part means writing one descriptor deletes one line, and the count is
the work. A part called from four routes appears four times; that is not noise
either, because one descriptor retires all four and the spread is how far that
one part reaches.

## 12. A value that must not be written down is not bound

A header travels with the exchange. It lands in message history, in the log line
somebody adds while debugging, and in the error notification that goes out when
the route fails. So **a value that must not be recorded must not be bound** — to
a header, a property or the body — and §11's habit of putting a route's settings
on the canvas does not outrank that.

The rule is about the **value**, not about `cms:loadYaml`. Binding something to
a header is not one operation's privilege: `cms:getProperties` publishes JCR
properties, `cms:load` and `cms:loadAsString` put a whole file in the body, and a
part's `outputs=` hands back whatever it produced. Naming one of them would
close one door in a room with four.

### How it is enforced

Nothing can decide by looking whether a value is a secret. An application can
say so, and then the rule is checkable, which is the difference between a rule
and a preference (§6):

```yaml
# /etc/eip/secrets/commerce.yml
secrets:
  - path: /etc/commerce/config/shopify.yml
    keys:
      - adminApi.clientSecret
      - webhookSecret
  - path: /etc/commerce/config/access_token
```

One file per application, beside its routes and its aggregators, so removing the
application removes the declaration too. A binding that would publish anything
declared here is refused when the route is added.

**`keys` narrows a declaration to part of a document**, and that matters more
than it looks: a settings file usually holds one secret among twenty, and
declaring the whole file would refuse the nineteen legitimate bindings — which
would get the declaration deleted rather than the bindings fixed. A key covers
what is under it, and binding the whole document (`=document`) is refused by any
declaration on that path, because it publishes everything.

### What it does not reach

**A part's `outputs=`.** A part can read a secret and hand it back, and no
declaration of paths can see that happening. That is not a hole left open by
accident: it is why the descriptor asks a part to declare what it produces, and
why a part that depends on a secret says so with `reads:` rather than by
publishing it. `getAccessToken.groovy?outputs=shopifyAccessToken` was exactly
this shape, and it was deleted rather than declared.

**And forgetting to declare a new secret.** The declaration is a list somebody
maintains. That is still stronger than a sentence in a document, which does
nothing at all when it is broken — but it is worth being plain that this rule
protects what has been declared, not what is secret.

### Depending on a secret anyway

Read it inside the part that needs it, and declare the dependency:

```groovy
//   reads:
//     /etc/commerce/config/shopify.yml
```

What a reader needs to see is that the route depends on the file. Not what is in
it.

## 13. A commit is a contract, not a tuning knob

`cms:commit` says **"what came before this must be durable before what comes
after."**

```xml
<toD id="item-clear" uri="cms:remove?context=itemContext&amp;path=${header.markerPath}"/>
<toD id="item-commit-clear" uri="cms:commit?context=itemContext"/>
<toD id="item-recompute" uri="cms:/etc/…/recompute.groovy?context=itemContext&amp;inputs=orderId"/>
<toD id="item-commit-recompute" uri="cms:commit?context=itemContext"/>
```

The sales-fact drain deletes a pending marker *and commits it* before
recomputing, because a concurrent source event has to be able to re-create the
marker and have the next tick pick it up. Reverse the two and an update is lost.

**A batch is a different problem, and the answer is not an intermediate commit.**
A hundred thousand pending changes in one session is a memory problem and a very
long rollback, so a large batch has to become durable as it goes. Committing
inside one shared session does that, but it ties the items together: item N's
work only lands at item N+1's commit, so a failure in N+1 rolls back N.

**Items of a batch should be independent**, which means **a session per item**
rather than a shared session with commits inside it. There used to be a
`cms:commitCheckpoint?every=N` node for the shared-session form; it was removed,
and nothing was lost — the one route that would have used it wanted a session per
order anyway, for exactly the reason above.

## 14. An id names what the node does

Every node has an id (§5), and a route that satisfies that rule can still leave
nothing behind:

```xml
<choice id="payment-choice-3">
<choice id="payment-choice-4">
<choice id="payment-choice-5">
```

Three nested decisions, all named, and the names say nothing. Renamed from what
each one decides, the same three read:

```xml
<choice id="payment-kind-is-money-in">
<choice id="payment-status-success">
<choice id="payment-cash-in">
```

which is where "what counts as money coming in" is written down — it had existed
only inside the predicates until then.

**The id is what message history shows.** A person looking at a failed exchange
sees the id of the node it failed at, and nothing else about that node. A name
that does not say what the node does leaves them where they would have been with
no id at all, which is the state §5 exists to end.

**A numeric suffix is the signal.** `-2`, `-3`, `-4` in a run usually means the
ids were filled in to satisfy the rule rather than to answer the question the
rule is asking. Not always: a route with genuinely three of the same thing may
number them, and there is no way to tell those apart by looking.

**So this is advice, not a rule** (§6). A check that refused every id ending in a
digit would refuse correct ones, and a rule that refuses a correct spelling gets
removed rather than followed.
