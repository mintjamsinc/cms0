# BPM — app, subject, body and the record of a process

What a process says about itself to the people in it, and what the Tasks app
shows of it:

- **app** — the Webtop app the process belongs to. Shown as a badge beside the
  subject in the inbox, the history, the list of processes to start and the
  bar above a form.
- **subject** — what the process is about, like the subject of a mail. It
  leads each row of the inbox and the history; the task name goes under it.
- **body** — text the process shows at a step, made of its variables: an
  error message a service returned, an amount. The history shows it at its
  step.
- **record** — who took a step and when, kept by the platform for every step
  that is recorded (below), with the body there. The history of a process is
  the line of its records, its user tasks and its comments.
- **comment** — what a person wrote: the note given when the process was
  started, the comment given with a decision, a note left for whoever takes
  a task over, the note given when something was published. Kept as Camunda
  comments (`TaskService.createComment`) with who wrote them and when -
  never as process variables. See "Comments" below.

All of it is declared in the BPMN. A process that declares nothing looks as
it did before: the task name leads the row, the process name is under it.

Implementation: `org.mintjams.rt.cms.internal.bpm.record` (`ProcessRecords`,
`ProcessRecordParseListener`), the BPM GraphQL fields below, and the Tasks app
(`webtop/src/webtop/apps/tasks`).

## Declaring them

Camunda extension properties, on the process and on its steps:

```xml
<bpmn:process id="expense-claim" name="Expense Claim" isExecutable="true">
  <bpmn:extensionElements>
    <camunda:properties>
      <camunda:property name="app" value="expenses"/>
      <camunda:property name="subject" value="Expense claim: {{title}} ({{amount}})"/>
    </camunda:properties>
  </bpmn:extensionElements>

  <bpmn:startEvent id="StartEvent_claim" name="Claimed">
    <bpmn:extensionElements>
      <camunda:properties>
        <camunda:property name="record" value="true"/>
      </camunda:properties>
    </bpmn:extensionElements>
  </bpmn:startEvent>

  <bpmn:userTask id="UserTask_review" name="Review the Claim" camunda:assignee="${manager}"/>

  <bpmn:serviceTask id="ServiceTask_pay" name="Pay">
    ...
    <bpmn:boundaryEvent id="Event_refused" name="Payment refused" attachedToRef="ServiceTask_pay">
      <bpmn:extensionElements>
        <camunda:properties>
          <camunda:property name="body" value="{{paymentError}}"/>
        </camunda:properties>
      </bpmn:extensionElements>
      ...
  ...
```

| Property | On | Meaning |
|----------|----|---------|
| `app` | the process | The relPath of the Webtop app (the app's folder under `/usr/share/webtop/apps`, the id its own `app.<relPath>.*` messages use). The badge shows the app's title in the reader's language (`app.<relPath>.title`, else the `title` of its app.yml, else the relPath itself). |
| `subject` | the process | The subject template. |
| `body` | a start event, a user task, an intermediate throw event, an end event, a catch event, a receive task | The body template of that step. Declaring it also makes the step recorded (below). |
| `record` | the same steps | `true`: the step is recorded without a body — who took it and when. For an outcome whose words were written at the task before it. |

### Templates

A subject or body is text with `{{name}}` where the value of the process
variable `name` goes — the double braces the formKey token
`?t={{lastModified}}` already uses. The engine evaluates `${...}` itself (JUEL)
and never touches `{{...}}`, so the template reaches the reader as written.

- A name is a letter or `_`, then letters, digits or `_`; spaces inside the
  braces are allowed (`{{ title }}`).
- Literal text and variables mix freely: `Expense claim: {{title}} ({{amount}})`.
  A template may also be a variable alone (`{{paymentError}}`) or text alone.
- A variable that is not set leaves nothing behind.
- Values are put in **in the reader's settings**: a date in their time zone
  and date format, a number in their number format. Strings are shown as
  they are; a list is joined with `, `; a bytes variable is read as UTF-8
  text (how a script passes a text longer than 4000 characters). A value is
  cut at 4000 characters, ending in "…".
- Where the values come from: the subject reads the process-level variables
  (as they are now; from the history once the process ended, where a
  process-level variable wins over one of the same name in an inner scope).
  A body reads the variables visible from its step, task-local ones included.

### Translations

The template in the BPMN is the text in the language it is written in. A
bundle may translate it under a convention key, like the names of processes
and tasks:

| Text | Key |
|------|-----|
| subject | `bpm.process.<processKey>.subject` |
| body of a user task | `bpm.process.<processKey>.task.<taskDefinitionKey>.body` |
| body of any other step | `bpm.process.<processKey>.event.<activityId>.body` |
| name of a step other than a user task | `bpm.process.<processKey>.event.<activityId>.name` |

(Process and task names keep their keys: `bpm.process.<key>.name`,
`bpm.process.<key>.task.<taskDefinitionKey>.name`.)

A translation is a template too, with the same `{{name}}` placeholders. It is
**not** run through ICU MessageFormat, so its braces are safe. It may use only
the variables the BPMN template names: those are the values the server hands
out (see "What is exposed").

```json
{
  "bpm.process.expense-claim.subject": "経費精算「{{title}}」（{{amount}}）",
  "bpm.process.expense-claim.event.StartEvent_claim.name": "申請"
}
```

Bundles are the global ones (`/etc/i18n/<unit>.<locale>.json`) that ship with
the process, as for process names.

## Records

A step is recorded when the engine passes it:

| Step | Recorded | When |
|------|----------|------|
| start event | when it declares a `body` or `record`, or is a message start event | the process (or a sub-process) starts |
| user task | when it declares a `body` or `record` | the task is completed |
| intermediate throw event, end event | when it declares a `body` or `record` | it is reached — how a BPMN names an outcome ("Approved", "Returned") |
| message catch event (intermediate or boundary), receive task | always | it is triggered: somebody acted on the process from outside |
| other catch events (timer, signal, ...) | when it declares a `body` or `record` | it is triggered |

A step that is cancelled (a boundary event interrupted it, the process was
deleted) is not recorded.

A record holds:

- **who** — the engine's authenticated user at that moment, else (a user
  task) its assignee. The GraphQL mutations run as the caller; a script names
  the user with `ProcessAPI.createProcessStarter().setUserId(...)`,
  `createMessageCorrelator().setUserId(...)`, or by setting
  `engine.identityService.setAuthenticatedUserId(...)` around its call. A step
  nobody drove (a timer, a route without a user) has no one.
- **when**, the **step** (activity id, type and name; for a user task its id),
- the **body template as written** and the **values of the variables it
  names at that moment** — a later change of the variable does not change
  the record.

Records are kept as Camunda comments of the process instance the step ran
in (action `WebtopRecord`; a call activity's child process keeps its own), so
they live and are cleaned up with the rest of its history (history time to
live, removal time). A script that reads the comments of a process or task
directly sees them beside the comments people wrote; tell them apart by the
action (`AddComment` for those). At history level `none` nothing is
recorded, and steps passed before this version have no records.

## Comments

What a person writes in a process is a Camunda comment
(`TaskService.createComment`), not a process variable: the engine keeps who
wrote it and when, and several people who hold a task in turn each keep
their own.

| Written | How | Kept as |
|---------|-----|---------|
| with a completion | GraphQL `completeTask(input: {comment})`; a form: `host.completeTask(variables, {comment})`; a script: `ProcessAPI.createTaskCompleter().setComment(...)` | a comment of the task, in the same transaction as the completion |
| while holding a task (a note for whoever takes it over) | GraphQL `addTaskComment(taskId, message)` - the assignee (an Admin or a Supervisor on any task); the Tasks app's comment button | a comment of the task |
| when a process is started | `ProcessAPI.createProcessStarter().setComment(...)` | a comment of the process (no task), in the same transaction as the start |
| when a process is told something | `ProcessAPI.createMessageCorrelator().setComment(...)` | a comment of the process, in the same transaction as the message |

Written in the same transaction, a comment on a process that the start or
the message ends still gets the process's removal time; one written later
on an ended process would not, and history cleanup would never remove it -
so the platform offers no way to comment on an ended process.

Comments are kept at every history level but `none`, where the engine
refuses to write or read them (`activity` keeps them too). `Query.
processCommentsEnabled` (a form: `host.commentsEnabled`; a script:
`ProcessAPI.isCommentsEnabled()`) tells which: apps hide their comment
fields when it is false. The GraphQL mutations then refuse a comment; the
script API writes none and goes on.

When the process needs the words too - a script after the task that keeps
them with a record of the app's own, or sends them on - the app passes them
once more as a **transient** variable (GraphQL `valueInfo: {transient:
true}`; `TaskCompleter.setTransientVariable`): the process sees it until it
next waits, and it is never stored nor kept in the history. It cannot take
the name of a stored variable of the same scope (the engine refuses it).
A script reads it off `execution` when a process started by an earlier
version of the BPMN does not list it among its inputs.

### Telling who did it from outside

A process that outlives the work it tracks (a request that waits to be
published, an order that waits to be shipped) should wait for what happens
next with a message event, and the app should tell it with a message as the
user who did it:

```groovy
ProcessAPI.createMessageCorrelator()
    .setMessageName('order-shipped')
    .setProcessInstanceId(processInstanceId)
    .setVariables([trackingNumber: trackingNumber])
    .setComment(note)
    .setUserId(user)
    .correlate();
```

The message event is recorded with that user, and the note they gave is
kept as their comment on the process, right after it (see "Comments").
Deleting a process instance instead records nothing:
the history shows it as cancelled, by nobody.

## GraphQL

| Field | |
|-------|---|
| `Task.app`, `ProcessDefinition.app`, `ProcessHistoryEntry.app` | `String` — the `app` property. |
| `Task.subject`, `ProcessHistoryEntry.subject` | `ProcessText` — the subject with the values as they are now (as they were at the end, for a finished instance). |
| `Task.latestRecord`, `ProcessHistoryEntry.latestRecord` | `ProcessRecord` — the last record of the instance whose body says something (a step recorded without words after it does not blank the preview); when no record has such a body, the last record. |
| `ProcessHistoryEntry.records` | `[ProcessRecord!]!` — every record, oldest first. Select it for a single entry, not for a list. |
| `Task.comments`, `ProcessHistoryEntry.comments` | `[ProcessComment!]!` — every comment of the process, oldest first: at its tasks (`taskId`) and on the process (`taskId` null). For a single task or entry. |
| `Task.latestComment`, `ProcessHistoryEntry.latestComment` | `ProcessComment` — the latest comment. |
| `Query.processCommentsEnabled` | `Boolean!` — whether comments are kept (history level other than `none`). |

```graphql
type ProcessText {
  key: String!        # the convention key (bpm.process.<key>.subject, ...)
  template: String!   # as the BPMN writes it
  params: JSON!       # name -> { type: string|number|boolean|date, value }
}

type ProcessRecord {
  id: ID!
  time: DateTime
  userId: String
  userDisplayName: String
  taskId: String          # the user task it belongs to; null for other steps
  activityId: String
  activityType: String    # startEvent, userTask, intermediateThrowEvent, endEvent,
                          # intermediateCatchEvent, boundaryEvent, receiveTask
  activityName: String
  body: ProcessText       # null when the step declares none
}

type ProcessComment {
  id: ID!
  time: DateTime
  userId: String          # the writer
  userDisplayName: String
  taskId: String          # null: written on the process itself
  message: String!
}
```

The server never finishes the text: it hands out the template and the values,
and the reader's Webtop renders them in the reader's language and settings
(`bpmProcessText()` in `webtop/src/webtop/composables/use-localization.ts`).
Each of these fields costs a lookup per process instance; select them only
where they are shown.

### What is exposed

The process history (`processHistory`, `processHistoryEntry`) deliberately
leaves process variables out: they carry what other participants entered. The
subject and the records show only the variables their templates name — the
process author decides what the people in the process see of it, as the
author of a mail decides what goes into its subject.

## The Tasks app

- **Inbox** — each row: the subject (cut with "…" when long; the app badge
  never gives way), the app badge, the task name, the latest words on one
  line (like a mail list's preview: the latest comment or the latest body,
  whichever was written last), and the due date or when it was created. A
  process without a subject keeps the old row: task name, then process name
  and business key (with a subject, the business key is left out). The
  keyword search also matches the subject, the app title and the preview.
- **Bar above the form** — the subject, the app badge and the task name, and
  (when comments are kept) a comment button: the comments of the process,
  and for the assignee a field to leave one - before handing the task over,
  say.
- **History** — each row: the subject, the app badge, the process name and
  its state, the latest words. The detail is one line of time: the records
  that belong to no user task (the start, an outcome, a message from outside),
  each followed by the comment its writer gave with it, and the user tasks
  with who held them. A comment written at a task stands among that task's
  operations ("Operations"), at the time and with the name of the person who
  wrote it - so each of the people who held it in turn shows what they
  wrote. The operations - who claimed the task, released it, reassigned it,
  completed it - are logged at history level `audit` (the default) and
  `full`; at `activity` the comments stand alone there. "Involved" counts
  everybody who held a task of the process, also somebody who claimed one and
  released it again.
- **Live** — the app listens to every task event and every process start and
  end of the workspace (`taskUpdated`, `processStarted`, `processEnded`) and
  reloads the list it shows; the query, run as the user, decides what they
  see. A task decided elsewhere — in another app, by another person — leaves
  the inbox, and its form closes, without a refresh. So does a task that
  left the chosen scope.
- **Forms** — `host.context.task` carries `app` (the relPath) and `subject`
  (rendered in the user's language); `host.commentsEnabled` and the comment
  methods are in `bpm-task-forms.md`.

## Notes for process authors

- Assign a task to the person it is for (`camunda:assignee`) when the process
  knows who that is: it is then in their inbox under "Assigned to me", the
  engine's task notice tells them (`webtop-notifications.md`), and the history
  shows it among the processes they took part in. Candidates
  (`candidateUsers` / `candidateGroups`) are for work anyone of a group may
  take; one task per person among several — everyone must approve, say — is a
  multi-instance activity with an assignee per instance.
- Keep what people write as comments, not variables: the form passes the
  comment with the completion, the app passes a note to the starter or the
  correlator. Every history level but `none` keeps them; at `none` the app
  shows no comment field. Do not give a user task a `body` of a variable the
  form fills (`{{comment}}`): the history shows the comment at the task
  anyway, with who wrote it.
- Name outcomes as events (an intermediate throw event "Approved" after the
  approval branch, an end event "Returned" on the other) and mark them
  `record` = `true`: the line of time then says what was decided and by
  whom, right after the task. Mark the start `record` = `true` too, so the
  start and the note given with it stand first.
- Use `body` for what the process itself has to say at a step, from its
  variables: the message of a refusal, an amount.
- The BPMN Modeler draws a sub-process and what is in it, but not a
  sub-process inside a sub-process (only the arrows inside it show): keep
  sub-processes one level deep if the process is to be edited there.
- Keep the process alive as long as the thing it tracks, and end it with a
  message when it is done, so its history has the last step too.
