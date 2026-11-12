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

In practice: those writes run in a context **of their own**, opened and closed
inside the script that makes them, so each commits immediately. What keeps a
script out of the transaction is not where it sits but which context it writes
through: a script node runs in the route's session whenever the exchange
property `mi:cms.context` holds one, so a metrics write placed between the open
and the close and written with `context.session` joins the transaction and is
discarded with it. Open a separate context
(`ScriptAPI.createServiceUserContext`) for the write, commit it, and close it
in a `finally`.

Where they sit relative to the commit is then a matter of ordering, not of
transactionality — a side effect that must follow a durable record goes after
the commit, and a receipt that must survive a rollback goes before the
transaction opens.

Nothing enforces this. A script that writes through `context.session` inside
the scope is in the transaction, whichever of the four kinds above it is, and
the difference is one an author has to see.

### The same rule, from the other direction

A counter that adds to what it read (`eip-change-operations.md` §8) locks its
node, adds, and commits before it lets the lock go. In a script that joined the
route's session that commit commits the rest of the transaction halfway
through, so such a counter only ever belongs in a script that owns its session.
That looks like a restriction on counters; it is the same rule again. A counter
that needs the lock is a counter that must not be in the transaction anyway.

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

An operation that reports whether it acted — `tryLock` returning `null`, a
comparison in front of a conditional write (`eip-change-operations.md` §8) — is
a guard only if something reads the answer. The script that lost the race
carries on into the critical section exactly like the one that won, unless a
`return` in the script, a `<filter>` or a `<choice>` says otherwise. Nothing
checks this, so it is a review rule.

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
always false. The ban moved into the language for a while, which refused it
when the route was added. That restriction has since been removed together with
the permissions: `jexl` now evaluates dot notation and method calls as written
(the example above is `true`), so avoiding them is a review rule (§7).

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
thing these rules exist to stop. `mi:conventions.pending` is no longer read by
anything — writing it is ignored — and no longer documented anywhere but here.

The route checker that enforced these rules was later removed as well. Nothing
refuses a route for breaking a convention when it is added now, the rules in the
table below included; every rule in this document is a review rule.

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
route readable.

| | Where | What it may do |
|---|---|---|
| A `<script><groovy>` node | a node on the flow | produce a value — arithmetic, text, a moment, a default — and put it on the exchange under a name that says what it is |
| `simple` / `jexl` | inside `<when>` / `<filter>` | decide yes or no, from values already computed |

The value gets a node and a name because that is what makes it visible: on the
canvas, in the log line that prints the exchange, in the exchange history, and
to the reviewer who wants to know where `cutoff` came from. A script may stop
its own work early — `return` after a failed guard — but the route's branching
is a `<choice>`, so that the two paths are two paths a reader can see.

### Which predicate language

**Equality and existence in `<simple>`. Grouping and regular expressions in
`<language language="jexl">`. Ordering only between values a script has
already made the same type.**

`==` and `!=` stay in Simple: they are the majority of predicates, they read
better, and `${exchangeProperty.status} == 'paid'` has nowhere to go wrong.
Reach for `jexl` when the condition needs parentheses, a name containing a
colon, or `=~`.

An ordering comparison between unlike kinds is wrong in both languages, and
wrong quietly: Simple's `<` and `>` coerce one side to the other's type and,
when that fails, compare the string forms, and `jexl` does much the same — a
stored moment against a `Date` becomes `"2026-07-29T…"` against
`"Wed Jul 29 …"`, which is an answer, always the same answer, and never the
question that was asked. The first route that needed `lastAlertAt < cutoff`
was true for every possible pair of instants. So the script that computes the
two values makes them the same type (`eip-change-operations.md` §7), or
compares them itself and puts a boolean on the exchange.

**A predicate does not calculate.** `header['a'] + header['b'] > 100` hides an
addition inside a condition; the addition is a line in the script, and then the
condition reads the result. The same goes for lower-casing an address before
comparing it. The reason is not tidiness — a value computed inside a predicate
is a value nobody can see on the canvas, in the log, or in the history.

### Writing a `jexl` predicate

```xml
<when>
    <language language="jexl">(property['txn_kind'] == 'sale' || property['txn_kind'] == 'capture')
        &amp;&amp; (empty(property['txn_status']) || property['txn_status'] == 'success')</language>
</when>
```

A predicate sees three variables — `header`, `property` (the exchange
properties) and `body` — and nothing else. There is no `exchange`:
`exchange.properties[...]` fails as an undefined variable when it is evaluated.

- **Bracket notation, never a dot into a name.** `header['commerce:status']`
  reads a name containing a colon, which Simple cannot do at all, and an absent
  key is null rather than an error.
- **No method calls.** A convention, not something the language refuses —
  `header['email'].toLowerCase()` and dot notation are evaluated as written. A
  predicate that lower-cases before comparing has a transformation hidden
  inside a condition, and the transformation is a line in the script in front.
- **`empty(x)`** is true for null, an empty string, and an empty collection or
  map. It is the usual way to say "not set".
- **`=~` is both** membership and pattern: `header['status'] =~
  ['paid','authorized']` against a list literal, `header['sku'] =~ '^SKU-\d+$'`
  against a string.
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

**`orderBy` and `order` are written literally.** `orderBy` names one of a fixed
set of words — for `queryTasks`: `createTime`, `dueDate`, `priority`, `name`,
`assignee`, `id` — and a word the operation does not know is refused. Nothing
refuses `orderBy=${header.sortColumn}` as such: a `<toD>` resolves it to text
before the component sees it. It is still not written, because a sort order
chosen by a header is a sort nobody reading the route can see. `order` is
descending for `desc` and ascending for anything else. `maxResults` is data and
may come from configuration.

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
<split id="sla-per-task" aggregationStrategy="#commerce.Tally">
```

**The strategy is a Groovy script the application owns.** It is a `.groovy`
file under `/etc/eip/strategies/aggregation/`, and its name is its path below
that folder without `.groovy`, with `/` written as `.`:
`/etc/eip/strategies/aggregation/commerce/Tally.groovy` is `#commerce.Tally`
(`#commerce/Tally` finds it too). The folder is part of the name. commerce's
`#commerce.LatestExchange` (`commerce/LatestExchange.groovy`) is a real one. Like
a route it is deployed by uploading a file and withdrawn by deleting it, and
adding a column to a summary line does not need a platform release. The platform
supplies the adapter that calls the script and no arithmetic at all — what is
worth counting is the application's business.

**The script is given exchanges and returns the one to keep.** Its bindings are
`oldExchange` (what has been accumulated so far; `null` on the first call),
`newExchange` (this item) and `inputExchange` (when Camel passes it). It runs in
the context named by the exchange property `mi:cms.context` of `newExchange`
when there is one, and in a throwaway context otherwise, with the usual bindings
(`ScriptAPI`, `log` and the rest). It can therefore reach the repository; that a
strategy folds headers and does nothing else is a convention.

**What leaves the split is what the strategy returned.** After a `<split>` with
a strategy the message is the aggregated result (Camel's rule for a split). A
route that needs the body that entered the split afterwards saves it in a
property before the split, or has the strategy keep it.

### The contract a route has to keep

What follows is the route's side of the contract when the application has
written a strategy that sums `tally_*` headers.

**An item reports its own contribution, never a running total.** Sub-exchanges
inherit the parent's headers, so a node that read the inherited value and added
to it would be counted twice: once by itself and once by the strategy.

**Every tally is set on every path, not only where it is earned.** The inherited
value is what stays otherwise. The shape that keeps this readable is to set them
all to their zero at the top of the split body and overwrite the ones a branch
earns:

```xml
<split id="sla-per-task" aggregationStrategy="#commerce.Tally">
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
all. The component reads its URI parameters by hand and then empties the map,
which suppresses the check Camel would otherwise have made, so a misspelling
was not an error — it was a permanent silent no-op, and on a `<toD>` whose URI
is built per message, "nothing says so" could last for months.

The operation moved into Groovy and the failure moved with it, to a different
place: **a script reads what it names.** `exchange.getProperty('orderPth')` is
null, null is a value, and it travels — to a `getResource(null)`, or worse, to
a property written as the string `"null"` three nodes later. So:

- **A script reads its inputs at the top, by name, and refuses null for what
  it requires.** One line per input, where a reviewer can match the names
  against the `<setProperty>` nodes that feed the script:

  ```groovy
  String orderPath = exchange.getProperty('orderPath');
  if (orderPath == null) {
      throw new IllegalStateException('orderPath is not set');
  }
  ```

  `?: 'default'` is for a value that genuinely has a default, and the default
  is then a decision the script has made visibly, not a misspelling it has
  absorbed.

- **What a script puts on the exchange is named once, at the top, in a
  comment**, next to what it reads. That is the §11 contract for a part,
  applied to an inline script. Nothing checks it; it is what a reader uses.

- **The `cms:` nodes that remain are checked by eye.** `cms:login` reads
  `runAs` and its output binding (`@property.x=context` / `@header.x=context`);
  `cms:commit`, `cms:rollback` and `cms:logout` read `context`; `cms:lock` reads
  `path`, `isDeep`, `isSessionScoped`, `timeoutSeconds` and its output binding
  (`@property.x=lock`); `cms:unlock` reads `path`. `cms:lock` and `cms:unlock`
  also read `context`, and `runAs` when they own their session. Anything else
  on one of them is ignored without a
  word, which is the reason the list is this short and this document says what
  is on it.

**A word is still a word.** `isSessionScoped=yes` is read as `false`, because
the parser knows `true` and nothing else; an option that takes a word takes
exactly the words the operation knows, and a route author checks them against
this document rather than against the absence of an error.

---

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

So a part declares its contract, in a comment block at the top of the file.
Nothing compares the two any more — the check that did was removed with the
route checker (§6) — so the descriptor is what a reader and a reviewer use:

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

### What a reviewer looks for

Nothing here is refused when the route is added; these are what the descriptor
lets a reader see.

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

While the check existed it was staged with the same mechanism as §6, the
exemption naming the part (`undescribed-parts:<script path>`), so that writing
one descriptor deleted one line. That mechanism is gone with the check, and
`mi:conventions.pending` is not read.

## 12. A value that must not be written down is not bound

A header travels with the exchange. It lands in message history, in the log line
somebody adds while debugging, and in the error notification that goes out when
the route fails. So **a value that must not be recorded must not be bound** — to
a header, a property or the body — and §11's habit of putting a route's settings
on the canvas does not outrank that.

The rule is about the **value**, not about one way of reading it. Putting
something on the exchange is not one operation's privilege: a script that
reads a settings file with `YAML.parse` and sets a key as a property, one that
copies JCR properties onto the exchange, one that puts a whole file in the
body, and a part's `outputs=` all publish what they read. Naming one of them
would close one door in a room with four.

### Nothing enforces it

Nothing can decide by looking whether a value is a secret. For a while an
application declared the paths and keys that hold secrets in
`/etc/eip/secrets/<app>.yml`, and a binding that would publish one of them was
refused when the route was added. That check was removed with the route checker
(§6): the platform no longer reads the file, nothing refuses a binding, and
leaving a declaration there does nothing.

So this is a review rule. What a reader looks for is a script that reads a
secret and puts it on the exchange, and a part whose `outputs=` hands one back.

### Depending on a secret anyway

Read it inside the part that needs it, and declare the dependency:

```groovy
//   reads:
//     /etc/commerce/config/shopify.yml
```

What a reader needs to see is that the route depends on the file. Not what is in
it.

## 13. A commit is a contract, not a tuning knob

A commit says **"what came before this must be durable before what comes
after."**

```groovy
// The marker is gone, durably, before the recompute starts: a concurrent
// source event has to be able to re-create it and have the next tick pick
// it up. Reverse the two and an update is lost.
context.session.getResource(markerPath).remove();
context.session.commit();
commerce.SalesFacts.create(context).recompute(orderId);
context.session.commit();
```

The sales-fact drain deletes a pending marker *and commits it* before
recomputing, and the comment says why. A commit without a reason next to it is
a commit a reader has to reverse-engineer, and the first thing a later edit
will move.

**A batch is a different problem, and the answer is not an intermediate commit
every N items.** A hundred thousand pending changes in one session is a memory
problem and a very long rollback, so a large batch has to become durable as it
goes. Committing every N items does that, but it ties the items together: item
N's work only lands at item N+1's commit, so a failure in N+1 rolls back N.

**Items of a batch should be independent**, which means **a commit per item,
with a rollback per failure** — what the commerce queue routes do inside their
`<split>`: each message is processed, committed, and on failure rolled back and
marked, so that the next message starts from a clean session. A context per
item does the same at a higher cost. There used to be a
`cms:commitCheckpoint?every=N` node for the shared-session form; it was removed,
and nothing was lost — the one route that would have used it wanted a commit
per order anyway, for exactly the reason above.

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
