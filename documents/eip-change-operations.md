# Changing content from a route

How a route reads and writes the repository, and the rules that decide what
each write guarantees. For the session the writes run inside — opening it,
committing, closing, locking — see `eip-session-lifecycle.md`.

**The writes are Groovy.** A `<script><groovy>` node that runs inside the
route's context (`mi:cms.context`, see the lifecycle document) has the
session, the `Resource` API, `XPath`, `JSON`, `YAML`, `ISO8601` and `log`, and
writes content in the same lines that decide what to write. Every route the
platform ships and every application route does it this way; the `cms:`
producers that used to stand for these operations (`cms:store`,
`cms:setProperties`, `cms:query`, `cms:remove` and the rest) are deprecated and
scheduled for removal, and section 9 maps each of them to the Groovy that
replaces it. What stays declarative is the entry point, the branching, the
loops, and — if the route wants it — the session skeleton.

---

## 1. Where a write goes

A script node either **joins the route's session** or **owns one**, and the
difference is the whole of what "transaction" means here.

- **Joined**: the exchange property `mi:cms.context` holds the context the
  route opened. `context.session` is that session; nothing the script writes is
  durable until something calls `context.session.commit()`, and closing the
  context discards what was not committed.
- **Owned**: the script opens a context of its own with
  `ScriptAPI.createServiceUserContext(user)`, commits through it, and closes it
  in a `finally`. Its writes are durable the moment it commits, and a rollback
  of the route's session does not undo them.

Which one a script should be is decided by the rule in `eip-conventions.md`
§1: what records that the business failed — metrics, audit entries, the receipt
of an inbound payload — must not roll back with it, so it owns its session.
Everything else joins.

A script that writes through a joined session **and** commits is making a
decision for the whole route: everything pending becomes durable, not just its
own lines. That is the right thing when the script is the unit of work (the
sweep commits per member, the queue drain commits per message) and a mistake
when it sits in the middle of a larger transaction. Say which it is in a comment
next to the commit.

---

## 2. The resource

```groovy
def file = context.session.getResource('/content/commerce/orders/1001/index');
```

`getResource(path)` never fails for a path that does not exist; it returns a
resource whose `exists()` is false, which is what lets one line serve both the
create and the update case. The API in one table:

| | |
|---|---|
| Existence and shape | `exists()`, `isCollection()`, `getParent()`, `getResource(relPath)`, `list()` |
| Create | `createFile()`, `createFolder()`, `getOrCreateFile()`, `getOrCreateFolder()`; the `(name)` forms create a child |
| Content | `getContent()` (a `String`), `getContentAsStream()`, `getContentAsByteArray()`, `write(content)`, `setContentType(type)`, `setContentEncoding(enc)` |
| Properties | `getProperty(name)`, `hasProperty(name)`, `getPropertyKeys()`, `getProperties(pattern)`, `setProperty(name, value)`, `removeProperty(name)` |
| Move and delete | `moveTo(absPath)`, `copyTo(absPath)`, `remove()` |
| Locks and versions | `tryLock(...)`, `lock(...)`, `unlock()`, `getLock()`; `addVersionControl()`, `checkout()`, `checkin()`, `checkpoint()` |
| Metadata | `getCreated()`, `getLastModified()`, `getCreatedBy()`, `getLastModifiedBy()` |
| Access | `canRead()`, `canWrite()`, `canRemove()`, `getAccessControlList()` |

Every setter returns the resource, so a create-and-fill reads as one chain:

```groovy
context.session.getResource(path).createFile()
        .setContentType('application/json')
        .write(JSON.stringify(payload))
        .setProperty('commerce:receivedAt', new Date());
```

A file's content is on its `jcr:content` child, where its MIME type, encoding
and custom properties live too; the API hides that, and a property set on a
file lands where the content browser and a query expect it.

---

## 3. Properties

### Types are the value's type

`setProperty(name, value)` writes a JCR property of the type the Groovy value
has: a `String`, a `long`/`int`, a `double`, a `BigDecimal`, a `boolean`, a
`Date` or `Calendar`, a `byte[]` or `InputStream`, or another `Resource` (a
reference). An array of any of these writes a multi-valued property. There is
no `type=` to misspell: the value decides.

Two consequences:

- **A number read out of JSON is whatever the parser made it.** `JSON.parse`
  gives `Integer`, `Long`, `BigDecimal` or `Double` depending on the text, so a
  price written straight from the payload is a `DECIMAL` for `"12.50"` and a
  `LONG` for `12`. A property that has to compare and sort across orders is
  written with one explicit type: `new BigDecimal(order.total_price as String)`
  or `order.quantity as long`. **One property name, one type, repository-wide**
  — the index stores a property under its type, and a value of another type in
  the same name is skipped by the index without a word.
- **A moment is a `Date`.** The repository stores it as a `DATE`, reads it back
  as a `Calendar` (`getProperty(name).date`; `.date.time` when a `Date` is
  wanted), compares and sorts it correctly in a query, and the content browser
  shows it as a moment. Convert at the boundary
  with `ISO8601.toDate(text)` on the way in and `ISO8601.format(date)` on the
  way out (section 7). A moment stored as text is a moment nothing can order.

### Reading

`getProperty(name)` returns a `Property` with typed getters — `string`, `long`,
`int`, `double`, `decimal`, `date` (a `Calendar`), `boolean`, `resource` for a
reference, `stringArray` and the other `…Array` forms for a multi-valued one,
and `value` for whatever the stored type is. A property that is not there throws; guard with
`hasProperty(name)` when absence is a legitimate state, and let it throw when
it is not — a status that cannot be read is a node that should not be processed,
and a null that travels on to become an empty string somewhere else is harder to
find.

### Null removes

```groovy
file.removeProperty('commerce:errorMessage');
```

`setProperty(name, null)` removes the property too, so a script that copies a
map onto a node clears whatever the map says is gone. Decide which one you
meant: a map that *lacks* a key leaves the property as it was, a map whose key
is *null* removes it.

### Copying a map onto a node

The commerce routes pick sixty values out of a webhook payload and write them
under a prefix. The shape that does it, with the type rule above applied where
it matters:

```groovy
def order = JSON.parse(exchange.in.getBody(String));
def index = context.session.getResource(exchange.getProperty('orderIndexPath')).getOrCreateFile();
[
    'shopify:id'              : order.id as String,
    'shopify:email'           : order.email,
    'shopify:financialStatus' : order.financial_status,
    'shopify:currency'        : order.currency,
    'shopify:totalPrice'      : new BigDecimal(order.total_price as String),
    'shopify:createdAt'       : ISO8601.toDate(order.created_at),
    'shopify:updatedAt'       : ISO8601.toDate(order.updated_at),
].each { name, value ->
    index.setProperty(name, value);
};
```

The names are visible, the types are visible, and a key the payload lacks is a
null that removes rather than a string `"null"` that lands. The older shape —
sixty `<setProperty><jsonpath>` nodes feeding a script that copies every
exchange property onto the node — still works and is not wrong; it costs
`resultType` and `suppressExceptions` on every line and hides the type rule.

### Multi-valued properties, one element at a time

```groovy
def pending = file.hasProperty('commerce:pending') ? (file.getProperty('commerce:pending').stringArray as List) : [];
if (!pending.contains(orderId)) {
    file.setProperty('commerce:pending', (pending + orderId) as String[]);
}
```

Read, change, write back. A set kept this way has no duplicates because the
script checked; the `as String[]` is what makes it a multi-valued `STRING`
rather than a single `STRING` holding a list's `toString()`.

---

## 4. Files, folders, moves and deletions

```groovy
// A file, created with its parents.
def receipt = context.session.getResource("/var/cache/commerce/queue/${name}");
receipt.getParent().getOrCreateFolder();
receipt.createFile().setContentType('application/json').write(body);

// Into another folder: the destination is the full path, name included.
receipt.moveTo("/var/cache/commerce/done/${name}");

// Gone, with everything beneath it.
context.session.getResource(stalePath).remove();
```

`moveTo` is JCR's move: naming an existing folder as the destination is an
error, not a move into it, and the parent of the destination has to exist.
`remove()` removes the subtree. Both stay in the session until the commit, and
a rollback undoes them — **a deletion belongs to the transaction that decided
on it.**

`getOrCreateFolder()` is the idempotent form for a folder that may already be
there; `createFolder()` on an existing path throws. A folder meant to carry a
lock is just a folder: `tryLock` adds `mix:lockable` itself.

---

## 5. Finding the work

The routes whose logic drifted furthest into Groovy were the ones that had to
**find** their work — prune what is past the retention window, recompute what
is stale, drain a queue. The query is Groovy too, and it has the one property a
query on a route must have: **values go in as variables, never by
concatenation.**

```groovy
def result = XPath.newBuilder()
        .append('/jcr:root/var/cache/commerce/queue//element(*, nt:file)')
        .append('[@commerce:platform = $platform and @commerce:status = $status')
        .append(' and @jcr:created <= $cutoff]')
        .append(' order by @jcr:created')
        .variable('platform', 'Shopify')
        .variable('status', 'pending')
        .variable('cutoff', cutoff)          // a Date
        .build()
        .limit(100)
        .execute();

exchange.setProperty('messagePaths', result.resources*.path);
exchange.setProperty('hasMore', result.hasMore());
```

`$name` in the statement, `.variable(name, value)` beside it. The builder
writes each value as the literal its type needs — a `String` quoted and
escaped, a `Date` as `xs:dateTime(...)`, a `Boolean` as `true()` / `false()`,
numbers bare — and refuses a `null` or a type it does not know. A `$name` inside
a quoted string in the statement is left alone. A value a route author
concatenates into the statement is a value the route author is escaping by
hand, and the apostrophe in a customer's name is where that goes wrong.

**`limit` is a decision, so write it.** A search with no ceiling is a cost
nobody wrote down, on a route that may one day run against a store a thousand
times bigger. `result.hasMore()` says the answer was cut short and
`result.total` says how many matched; **a sweep that saw part of its work must
be able to say so**, which is why the queue routes put `hasMore` on the
exchange and loop while it is true.

**Listing is not a small query.** The search index is updated asynchronously,
so a query can miss a node written moments ago. `resource.list()` reads the
children through the repository and sees what is there now. A route that
writes and then walks what it wrote lists; a route that searches a store nobody
just touched queries. A listing has no ceiling either — iterate and count, and
stop when the count is enough.

---

## 6. Settings

```groovy
def cfg = YAML.parse(context.session.getResource('/etc/commerce/config/sla.yml').content);
def openMinutes = cfg.open?.minutes;
if (openMinutes == null) {
    throw new IllegalStateException('sla.yml: open.minutes is required');
}
```

**A missing key is an error, and the script says so.** Binding null for a key
nobody wrote is how a threshold silently becomes zero and a monitor silently
stops monitoring. The consequence: **a config file has to be complete**, and
switching something off is done by setting its value, not by deleting the line.
`cfg.minutes ?: 60` is the shape that turned a hand-edited file that had lost a
line into a monitor with a threshold nobody chose; a default belongs in the
file, next to the comment that explains it, where an operator edits it.

A file that may legitimately be absent is `exists()` and a branch, written
where the reader can see that the feature is off because it was never
configured.

Whatever the YAML parser produced is what you get — `String`, `Integer`,
`Boolean`, `List`, `Map` — and a type change is a line: `as long`,
`ISO8601.toDate(...)`.

---

## 7. Values

What a route computes between reading and writing: arithmetic, text, moments.
In Groovy these are expressions, and the few rules that used to need their own
nodes are now habits.

**Numbers.** Keep money in `BigDecimal` from the first line:
`new BigDecimal(text)`, never `text as double`. Groovy's `+`, `-`, `*` on
`BigDecimal` stay `BigDecimal`; `/` needs a scale (`a.divide(b, 2,
RoundingMode.HALF_UP)`). A `String` in arithmetic is a `NumberFormatException`
or a coercion you did not mean — convert first, visibly.

**Moments.** `ISO8601` is the boundary between text and `Date`:

| | |
|---|---|
| Text in | `ISO8601.toDate('2026-07-29T12:00:00Z')`, `ISO8601.parse(text)` (an `Instant`) |
| Now | `new Date()` |
| Arithmetic | `Instant` and `java.time.Duration`: `Instant.now().minus(30, ChronoUnit.MINUTES)`; `Date.from(instant)` to store |
| Text out | `ISO8601.format(date)` — `2026-07-29T12:00:00.000Z`, UTC, three fractional digits, always |

Store a `Date`; render text only for a log line, a JSON body, or a message. A
cutoff computed with `Duration` measures elapsed time, not calendar days — right
for "more than N minutes since it was created", wrong for "the same time
yesterday", which needs `ZonedDateTime`.

**Text.** `toLowerCase(Locale.ROOT)` for an identifier (under a Turkish locale
`"I".toLowerCase()` is `"ı"`, and an email address normalised that way stops
matching itself); `strip()` rather than `trim()` so ideographic spaces go too;
`?: ''` only where an empty string is a value the reader would expect.

**JSON.** `JSON.parse(text)` gives maps and lists; `JSON.stringify(object)`
goes the other way. The body of a message is `exchange.in.getBody(String)`.

**Putting a value on the exchange.** A value the rest of the route branches on
goes on the exchange as a property, with a name that says what it is, and the
branch is a `<filter>` or `<choice>` with a `<simple>` predicate. A script that
both computes and branches hides the branch; a predicate that computes hides the
value (`eip-conventions.md` §7).

---

## 8. Conflicts and guards

### `InvalidItemStateException`

The repository's only concurrency signal is optimistic. A session that saves a
node another session has changed since it read it gets
`InvalidItemStateException`; there is no lock held in between. Absorbing that is
a read, a rollback, a re-read and another attempt:

```groovy
def attempts = 0;
while (true) {
    try {
        def metric = context.session.getResource(metricPath);
        def count = metric.hasProperty('commerce:count') ? metric.getProperty('commerce:count').long : 0L;
        metric.setProperty('commerce:count', count + 1);
        context.session.commit();
        break;
    } catch (javax.jcr.InvalidItemStateException ex) {
        context.session.rollback();
        if (++attempts > 6) {
            throw ex;
        }
        Thread.sleep(Math.min(500L, 20L << attempts));
    }
}
```

**The rollback is the whole session's.** A retry loop therefore belongs in a
script that owns its session, never in one that joined the route's — the
discard would throw away every other pending change in the transaction. That is
the same rule as `eip-conventions.md` §1 seen from the other side: a counter
that needs this loop is a counter that must not be in the transaction anyway,
because an order that failed and rolled back still has to be counted as failed.

**When the attempts are used up, throw.** A counter that quietly stops counting
is worse than one that fails, because nothing downstream can tell "no events"
from "not recording".

### A transition with its precondition

```groovy
def job = context.session.getResource(jobPath);
def claimed = job.getProperty('commerce:job_status').string == 'READY';
if (claimed) {
    job.setProperty('commerce:job_status', 'PROCESSING');
    context.session.commit();
}
exchange.setProperty('claimed', claimed);
```

Read, compare, write, commit. **This is not a compare-and-set**: another
session can interleave between the read and the commit, and reading it as
"atomic, therefore safe" is how you get a race that only shows up under load.

| To guarantee | Use |
|---|---|
| The precondition of a transition is visible | The comparison above, and a branch on its result |
| No other node is in the critical section | A **lock** around it (`tryLock`, lifecycle §6). Not a comparison |
| Concurrent writes to one node do not lose | The retry loop above, in a session of its own |

**A guard has to be branched on.** The script that did not write carries on
exactly like the one that did unless something reads `claimed`. `<filter>` on
the exchange property, or an early `return` in the script — either, but one of
them.

---

## 9. From `cms:` producers to Groovy

The producers below are deprecated. Each row is the one-line Groovy that does
the same thing inside a script node that has the route's context.

| Producer | Groovy |
|---|---|
| `cms:store?path=&content=` | `getResource(path).getOrCreateFile().write(content)` |
| `cms:load?path=` / `cms:getContent` | `getResource(path).content`, `.contentAsStream`, `.contentAsByteArray` |
| `cms:createFile`, `cms:createFolder` | `getResource(path).createFile()`, `.getOrCreateFolder()` |
| `cms:getMimeType`, `cms:setMimeType` | `.contentType`, `.setContentType(type)` |
| `cms:GetEncoding`, `cms:SetEncoding` | `.contentEncoding`, `.setContentEncoding(enc)` |
| `cms:getProperty?path=&name=` | `getResource(path).getProperty(name).string` (or `.long`, `.date`, `.value`, …) |
| `cms:setProperty?path=&name=&value=` | `getResource(path).setProperty(name, value)` |
| `cms:getProperties?path=&includes=` | `getResource(path).getProperties('commerce:*')` |
| `cms:setProperties?path=&includes=` | the `.each { name, value -> setProperty(name, value) }` in section 3 |
| `cms:exists?path=` | `getResource(path).exists()` |
| `cms:move?sourcePath=&destPath=` | `getResource(source).moveTo(dest)` |
| `cms:remove?path=` | `getResource(path).remove()` |
| `cms:query?statement=&limit=` | `XPath.newBuilder()...build().limit(n).execute()` (section 5) |
| `cms:list?path=` | `getResource(path).list()` |
| `cms:addVersionControl`, `cms:checkout`, `cms:checkin`, `cms:checkpoint`, `cms:uncheckout` | `.addVersionControl()`, `.checkout()`, `.checkin()`, `.checkpoint()` |
| `transform:*` (the value operations) | section 7 |

Every one of them, on a joined session, left its change for `cms:commit`; the
Groovy does the same, and a script that owns its session commits itself.

What is **not** on the list, and stays: `cms:login`, `cms:commit`,
`cms:rollback`, `cms:logout`, `cms:lock`, `cms:unlock` (the lifecycle
document, section 2), the `cms:<path>.groovy` script endpoint, and the
`eventadmin:` and `bpm:` components.
