# GraphQL API for cms0

cms0のコンテンツ管理機能をGraphQLで公開するための公式ドキュメントです。org.mintjams.rt.cms.internal.graphqlパッケージに実装されているASTベースのGraphQLエンジンに合わせ、現在利用できるクエリ/ミューテーション、レスポンス構造、パフォーマンスに関するベストプラクティスを整理しています。

## エンドポイント

```
POST /bin/graphql.cgi/{workspace}
GET  /bin/graphql.cgi/{workspace}
```

例:
- `http://localhost:8080/bin/graphql.cgi/system`
- `http://localhost:8080/bin/graphql.cgi/web`

`{workspace}` には cms0 上の JCR ワークスペース名を指定します。

## 認証

cms0 標準の認証・権限モデル (セッション or HTTP Basic) を利用します。`GraphQLExecutor` はゲストセッションを拒否するため、ログイン済みユーザーのみリクエストを送れます。アクセス権は実際の JCR セッションが持つ権限に委ねられます。

## リクエストフォーマット

### JSON ボディでの POST

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ node(path: \"/content/page1\") { path name nodeType } }"
  }'
```

### 変数の利用

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query GetNode($path: String!) { node(path: $path) { path name } }",
    "variables": {
      "path": "/content/page1"
    }
  }'
```

### GET リクエスト

シンプルなクエリであれば `GET /bin/graphql.cgi/{workspace}?query=...` もサポートされています (URL エンコード必須)。

## 実装の特長

### AST ベースの GraphQL パーサ

`GraphQLParser` がクエリ文字列と変数を解析して AST (Operation / Field / SelectionSet) を構築します。`GraphQLExecutor` は AST からルートフィールドを取得し、対応する Query/Muation Executor に処理を委譲します。これにより、フィールド単位での最適化や追加の静的検証が可能になっています。

### フィールド選択と NodeMapper

`NodeMapper.toGraphQL(node, selectionSet)` は SelectionSet に含まれるフィールドだけを JCR ノードから取り出します。SelectionSet が無い場合は後方互換のために全フィールドを返します (ただしパフォーマンスコストが上がります)。

### 主なノードフィールド

- `path`, `name`, `nodeType`
- 監査情報: `created`, `createdBy`, `modified`, `modifiedBy`
- `uuid` (mix:referenceable の場合のみ)
- nt:file 向け: `mimeType`, `size`, `encoding`, `downloadUrl`
- nt:folder 向け: `hasChildren`
- ロック情報: `isLocked`, `lockOwner`, `isDeep`, `isSessionScoped`, `isLockOwningSession`
- `properties { name type value }` (jcr:* は除外)
- フルテキスト検索用に `score` が付与されるケースがあります (`search` クエリ)

### コストの高いフィールド

以下のフィールドは内部で追加の JCR アクセスやイテレーションが発生するため、必要なときだけ選択してください。

1. **properties** - `PropertyIterator` を全走査します。
2. **lock 情報** - `LockManager` へのアクセスが必要です。
3. **nt:file 専用フィールド** - `jcr:content` ノードを参照します。
4. **フォルダ専用フィールド** - `hasNodes()` の呼び出しは巨大フォルダでコストがかかります。

### Relay 風ページング

`children`, `xpath`, `search`, `query` は GraphQL Relay Connection を模した構造を返します。
- `first` (デフォルト 20)
- `after` (Base64 `arrayconnection:{offset}` 形式)
- `edges[] { node { ... } cursor }`
- `pageInfo { hasNextPage hasPreviousPage startCursor endCursor }`
- `totalCount`

### ベストプラクティス

1. 必要なフィールドだけを指定して I/O を最小化する。
2. 大きなリストは必ず `first` / `after` でページングする。
3. プロパティ一覧が欲しい場合は `properties { name value }` のように必要なサブフィールドのみを要求する。

## 共通レスポンス構造

GraphQL のレスポンスは常に `{"data": { ... }, "errors": [...]}` を返します。`data` 直下のキーはクエリ/ミューテーション名と一致し、各ノードは NodeMapper が提供するフィールドのみを持ちます。Connection を返すクエリでは `edges`/`pageInfo`/`totalCount` を含む標準構造になります。

```json
{
  "data": {
    "children": {
      "edges": [
        {
          "node": {
            "path": "/content/page1",
            "name": "page1"
          },
          "cursor": "YXJyYXljb25uZWN0aW9uOjA="
        }
      ],
      "pageInfo": {
        "hasNextPage": true,
        "endCursor": "YXJyYXljb25uZWN0aW9uOjk="
      },
      "totalCount": 42
    }
  }
}
```

---

## Query オペレーション

### node(path: String!)

指定パスの単一ノードを返します (存在しない場合は `null`)。

```graphql
{
  node(path: "/content/page1") {
    path
    name
    nodeType
    created
    createdBy
    properties { name value }
  }
}
```

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{"query": "{ node(path: \"/content/page1\") { path name nodeType } }"}'
```

### children(path: String!, first: Int = 20, after: String)

指定ノード直下の子ノードを Relay Connection 形式で返します。

- `path` (必須)
- `first` (省略時 20)
- `after` (前ページの `endCursor`)

```graphql
{
  children(path: "/content", first: 10, after: "YXJyYXljb25uZWN0aW9uOjk=") {
    edges {
      node { path name nodeType modified }
      cursor
    }
    pageInfo { hasNextPage endCursor }
    totalCount
  }
}
```

### references(path: String!)

`mix:referenceable` ノードを参照しているノード一覧を返します。対象ノードが referenceable でない場合は空配列になります。

```graphql
{
  references(path: "/content/asset") {
    nodes { path name uuid }
    totalCount
  }
}
```

### accessControl(path: String!)

指定ノードに設定されている ACL エントリを返します。

```graphql
{
  accessControl(path: "/content/page1") {
    entries {
      principal
      privileges
      allow
    }
  }
}
```

`allow` は現在常に `true` (JCR API の制約) です。

### versionHistory(path: String!)

`mix:versionable` ノードのバージョン履歴を取得します。`versions` の各要素には `name`, `created`, `predecessors`, `successors`, `frozenNodePath` が含まれます。レスポンスには `baseVersion` と `versionableUuid` も含まれます。

```graphql
{
  versionHistory(path: "/content/page1") {
    versions {
      name
      created
      predecessors
      successors
      frozenNodePath
    }
    baseVersion
    versionableUuid
  }
}
```

### xpath(query: String!, first: Int = 20, after: String)

任意の JCR XPath を実行し、結果ノードを Connection 形式で返します。

```graphql
{
  xpath(query: "//element(*, nt:file)", first: 5) {
    edges { node { path name mimeType size } cursor }
    pageInfo { hasNextPage }
    totalCount
  }
}
```

### search(text: String!, path: String = "/", first: Int = 20, after: String)

`jcr:contains` を用いた全文検索です。`nt:file` を対象に `text` を検索し、`node.score` フィールドでスコアを返します。

```graphql
{
  search(text: "hello world", path: "/content", first: 10) {
    edges {
      node { path name nodeType score }
      cursor
    }
    totalCount
  }
}
```

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{"query": "{ search(text: \"hello\", path: \"/content\", first: 5) { edges { node { path name score } } } }"}'
```

### query(statement: String!, language: String = "JCR-SQL2", first: Int = 20, after: String)

XPath / JCR-SQL2 / JCR-SQL (非推奨) など任意のクエリステートメントを実行します。`language` は `xpath`, `SQL2`, `JCR-SQL2`, `SQL`, `JCR-SQL` などを指定できます (大文字小文字は正規化されます)。

```graphql
{
  query(statement: "SELECT * FROM [nt:unstructured] WHERE ISDESCENDANTNODE('/content')", language: "JCR-SQL2", first: 20) {
    edges {
      node { path name nodeType }
      cursor
    }
    totalCount
  }
}
```

---

## Mutation オペレーション

### createFolder(input: { path: String!, name: String!, nodeType: String = "nt:folder" })

親パス直下にフォルダ (任意ノードタイプ) を作成します。レスポンスは作成されたノードです。

```graphql
mutation {
  createFolder(input: { path: "/content", name: "docs" }) {
    path
    name
    nodeType
    created
  }
}
```

### createFile(input: { path: String!, name: String!, mimeType: String!, content: String!, nodeType: String = "nt:file" })

Base64 で渡された内容から `nt:file` + `jcr:content` を作成します。`content` は Base64 エンコード済みバイナリです。

```graphql
mutation {
  createFile(input: {
    path: "/content/docs"
    name: "hello.txt"
    mimeType: "text/plain"
    content: "aGVsbG8gd29ybGQ="
  }) {
    path
    mimeType
    size
  }
}
```

### deleteNode(path: String!)

ノードを削除し、`true/false` を返します。

### lockNode(input: { path: String!, isDeep: Boolean = false, isSessionScoped: Boolean = false })

ノードをロックします。必要に応じて自動的に `mix:lockable` を付与します。レスポンスはロック後のノード。

### unlockNode(path: String!)

指定ノードのロックを解除し、`true` を返します。

### setProperty(input: { path: String!, name: String!, value: Any!, type: String })

単一プロパティを作成 / 更新します。`type` を指定すると JCR PropertyType に変換されます。`Reference`/`WeakReference` を指定した場合は値に UUID を渡してください。

### deleteProperty(input: { path: String!, name: String! })

指定プロパティを削除します (存在しない場合はエラー)。

### addMixin(input: { path: String!, mixinType: String! }) / deleteMixin(...)

ノードに mixin を追加/削除します。結果として最新ノードが返ります。

### setAccessControl(input: { path: String!, principal: String, privileges: [String!], allow: Boolean = true, entries: [EntryInput] })

単一エントリ、または `entries` 配列で複数エントリをまとめて設定できます。

```graphql
mutation {
  setAccessControl(input: {
    path: "/content/page1"
    entries: [
      { principal: "user1", privileges: ["jcr:read", "jcr:write"], allow: true },
      { principal: "user2", privileges: ["jcr:read"], allow: true }
    ]
  }) {
    entries { principal privileges allow }
  }
}
```

### deleteAccessControl(input: { path: String!, principal: String! })

指定プリンシパルの ACL エントリを削除し、`true` を返します。

### checkin(path: String!) / checkout(path: String!)

`mix:versionable` ノードに対してチェックイン/チェックアウトを実行します。`checkin` は新しいバージョン情報を返し、`checkout` は boolean を返します。

### restoreVersion(input: { path: String!, versionName: String! })

指定バージョンにロールバックします。必要に応じて自動で checkout した上で `versionManager.restore()` を呼び出し、復元後のノードを返します。

---

## ACL で利用できる代表的な権限

- `jcr:read`
- `jcr:write`
- `jcr:modifyProperties`
- `jcr:addChildNodes`
- `jcr:removeNode`
- `jcr:removeChildNodes`
- `jcr:readAccessControl`
- `jcr:modifyAccessControl`
- `jcr:lockManagement`
- `jcr:versionManagement`
- `jcr:all`

## 内部アーキテクチャ

```
GraphQLRequest
    ↓
GraphQLParser (AST を生成)
    ↓
Operation / Field / SelectionSet
    ↓
QueryExecutor / MutationExecutor
    ↓
NodeMapper (必要なフィールドのみマッピング)
    ↓
GraphQLResponse
```

主要クラス:
- `GraphQLParser` … クエリ/変数を AST に変換
- `SelectionSet` … フィールド選択、ネスト選択を提供
- `QueryExecutor` / `MutationExecutor` … クエリ種別ごとの実処理を実装
- `NodeMapper` … SelectionSet に基づいて JCR ノードをシリアライズ

## 今後の改善

1. フラグメントのサポート
2. ディレクティブ (@include / @skip)
3. より詳細なクエリ検証とキューイング
4. バッチ実行時のさらなる最適化

## テスト

```bash
# ノード取得
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{"query": "{ node(path: \"/content\") { path name } }"}'

# フォルダ作成
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { createFolder(input: { path: \\"/content\\", name: \\"test\\" }) { path name } }"
  }'
```

## ビルド

Eclipse PDE からビルドする場合:

1. プロジェクトを右クリック
2. "Export" → "Plug-in Development" → "Deployable plug-ins and fragments"
3. 出力先を指定してエクスポート
