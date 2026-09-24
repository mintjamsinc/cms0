# Search facets

How a query counts its result set, and what the counts are named. The
statistical aggregations (`sum`, `avg`, `percentile`, ...) are covered in
`memos/xpath-facet-statistics.md`; this note is about the plain count facets
and the two ways of reading them.

## The clause

An XPath statement may end in a `facet accumulate` clause. Each entry is
counted over the statement's whole result set in one pass, whatever `limit` the
statement is executed with, so a `limit` of 0 is the cheapest way to get counts
alone:

```
/jcr:root/content//element(*, nt:file)[jcr:contains(., 'invoice')]
  facet accumulate @jcr:mimeType, top(@mi:tags, 20), @mi:orientation
```

| Entry | Counts |
| --- | --- |
| `@prop` | Every value of the property, with the number of documents carrying it. A multi-valued property contributes each of its values. |
| `top(@prop, N)` | The N most frequent values. |
| `range('label', lo <= @prop < hi)` | The documents whose numeric or date value falls in the range; one entry per label. |

Every String property is a facet dimension (the indexer adds a taxonomy facet
field for each value), so no configuration is needed for a new property. The
built-in `jcr:mimeType`, `jcr:encoding`, `jcr:createdBy` and
`jcr:lastModifiedBy` are dimensions too; `jcr:path`, `jcr:name` and
`jcr:identifier` deliberately are not.

## Negation

A predicate may be negative alone: `[not(jcr:like(@jcr:mimeType, 'image/%'))]`,
or a group of negations, `[(not(a) and not(b))]`. Lucene itself finds nothing
for a boolean clause made of prohibitions only, so the compiler anchors every
`not(...)` on all documents (`*:* AND NOT(...)`); the predicate then means
what it says, and `x or not(x)` is every document.

## Dimension names

A facet is reported under **the name the statement wrote**, without the
leading `@`:

| Entry | Dimension |
| --- | --- |
| `@jcr:mimeType` | `jcr:mimeType` |
| `top(@mi:tags, 20)` | `mi:tags` |
| `range('small', ...) ... @jcr:contentLength` | `jcr:contentLength` |
| `stats(@jcr:contentLength)` | `stats(jcr:contentLength)` |

The built-in properties are collected from internal index fields
(`jcr:mimeType` from `_mimeType`, `jcr:contentLength` from `_size`), but that
is not visible in the result: the internal name was what a reader used to see,
and it was never what the reader had written.

## Reading the counts

### GraphQL

The `xpath` and `query` fields return a `NodeConnection` whose `facets` are
the clause's results. They are computed only when selected, by re-running the
statement against the index with no node fetched, so a page that does not ask
for them costs nothing extra:

```graphql
query {
  xpath(query: "//element(*, nt:file) facet accumulate @jcr:mimeType", first: 0) {
    totalCount
    facets {
      dimension
      entries { label count number }
    }
  }
}
```

`count` is the document count; `number` carries the decimal value of a
statistic and is null for a plain count. The Webtop's
`ContentServiceGraphQL.xpathFacets(statement)` wraps exactly this query.

### Script API (Groovy)

```groovy
def result = XPath.createQuery(statement).limit(0).execute();
def facet = result.getFacetResult().getFacet('jcr:mimeType');
if (facet != null) {
    for (String label : facet.getLabels()) {
        int count = facet.getValue(label);
    }
}
```

`getFacet` accepts the name with or without the leading `@` and returns null
for a dimension the statement did not declare. `getNumber` / `getNumbers`
return the undivided decimal of a statistic.

## Access control

Both readers restrict the index query to the principals of the session that
runs it (`QueryAuthorizables`, the same rule the JCR query layer applies to a
query's nodes: nothing for a system, service or admin session, everyone for a
guest, everyone plus the user and its groups otherwise). The counts therefore
cover exactly the documents the caller could list, and a facet can never
reveal a value from a file the caller cannot read. The script API's
`getFacetResult()` and `getSuggestionResult()` used to skip this restriction;
they no longer do.

## Drill-down

The counts are taken over the statement's own result set. A facet UI that
narrows the statement (`... and @jcr:mimeType='image/png'`) therefore gets the
counts of the narrowed set, which is right for a single-choice drill-down. For
a multiple-choice dimension, where each value's count should ignore that
dimension's own filter, run one count-only statement per dimension with that
dimension's predicate left out.
