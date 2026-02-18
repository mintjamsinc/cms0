# GraphQL API for cms0

cms0のコンテンツ管理機能はGraphQLで公開されています。実装は `bundles/org.mintjams.rt.cms/src/org/mintjams/rt/cms/internal/graphql` にあり、`GraphQLExecutor` が `QueryExecutor` / `MutationExecutor` を通じて JCR セッションを操作します。`GraphQLParser` で簡易 AST を構築し、`NodeMapper` で必要なフィールドだけをシリアライズします。本書は 2025-11 時点の実装に合わせた仕様です。

## エンドポイント

```
POST /bin/graphql.cgi/{workspace}
GET  /bin/graphql.cgi/{workspace}?query=...
```

`{workspace}` には `system` / `web` など JCR ワークスペース名を指定します。例: `http://localhost:8080/bin/graphql.cgi/web`.

## 認証

`GraphQLExecutor` はゲストセッション (`org.mintjams.jcr.Session#isGuest()`) を拒否します。Basic 認証または通常のログインで JCR セッションを取得した状態で呼び出してください。

## リクエストフォーマット

標準的な GraphQL リクエスト本文を受け付けます。

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ node(path: \"/content/page1\") { path name nodeType } }"
  }'
```

GraphQL 変数も利用できます。

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query GetNode($path: String!) { node(path: $path) { path name } }",
    "variables": { "path": "/content/page1" }
  }'
```

クエリ文字列が短い場合は `GET /bin/graphql.cgi/{workspace}?query=...` も利用できます (URL エンコードは利用者側で実施)。

## レスポンス構造

レスポンスは GraphQL 仕様に沿い、`GraphQLResponse` が `{"data": {...}, "errors": [...]}` を返します。

- `data` … Executor が返した Map。成功時のみ含まれます。
- `errors[]` … 失敗時は `message` に加え `extensions.exception` / `extensions.exceptionMessage` が設定されます (例: 権限エラー、バリデーションエラー)。

## ノード表現 (NodeMapper)

`NodeMapper.toGraphQL()` が JCR ノードを以下のフィールド構造に変換します。フィールドは GraphQL のフィールド選択に応じて lazy に計算されます。

### 共通フィールド

- `path`, `name`, `nodeType`
- `created`, `createdBy`
- `modified`, `modifiedBy` (nt:file の場合は `jcr:content`, それ以外は `jcr:lastModified` / fallback)
- `uuid` ( `mix:referenceable` のみ)
- `properties[]` (後述)
- `isLocked`, `lockOwner`, `isDeep`, `isSessionScoped`, `isLockOwningSession` (要求時のみ計算)

### nt:file 固有

- `mimeType`, `size`, `encoding`
- `downloadUrl` (ノードパス)
- `jcr:content` からの `modified`, `modifiedBy`

### nt:folder 固有

- `hasChildren` ( `Node#hasNodes` )

### properties フィールド

- `properties` は `jcr:*` を除いた全プロパティを列挙します。
- 各要素は `{ name, propertyValue }`。`propertyValue` は Union で、`__typename`, `type`, `value` (単一) または `values` (配列) を返します。
- バイナリ値は Base64、日時は ISO-8601 (UTC) 文字列で返します。

`search` クエリのみ `node.score` を追加で埋め込みます。

## PropertyValue / PropertyValueInput

`PropertyValue` は JCR プロパティを GraphQL Union として表現します。

| GraphQL `__typename` | `type` | `value(s)` 内容 |
| --- | --- | --- |
| `StringPropertyValue` | `STRING` | 文字列 |
| `LongPropertyValue` | `LONG` | 64bit 整数 |
| `DoublePropertyValue` / `DecimalPropertyValue` | `DOUBLE` / `DECIMAL` | 数値 |
| `BooleanPropertyValue` | `BOOLEAN` | 真偽値 |
| `DatePropertyValue` | `DATE` | ISO-8601 文字列 |
| `BinaryPropertyValue` | `BINARY` | Base64 |
| `NamePropertyValue`, `PathPropertyValue`, `UriPropertyValue` | `NAME` / `PATH` / `URI` | 文字列 |
| `ReferencePropertyValue`, `WeakreferencePropertyValue` | `REFERENCE` / `WEAKREFERENCE` | 参照先 UUID |

配列プロパティの場合は `...PropertyValueArray` となり `values` に List が入ります。

`setProperties` ミューテーションでは `PropertyValueInput` を使用します。以下のうち **1 フィールドのみ** を指定してください (配列は `*ArrayValue` フィールドを利用)。2つ以上指定された場合は、バリデーションエラーとなります。

```
stringValue / stringArrayValue
longValue / longArrayValue
doubleValue / doubleArrayValue
decimalValue / decimalArrayValue
booleanValue / booleanArrayValue
dateValue / dateArrayValue
binaryValue
nameValue / nameArrayValue
pathValue / pathArrayValue
referenceValue / referenceArrayValue
weakReferenceValue / weakReferenceArrayValue
uriValue / uriArrayValue
```

日時は ISO-8601、Binary/ファイルデータは Base64 で渡します。Reference 系は UUID を指定してください。

## Relay Connection 共通仕様

`children`, `references`, `xpath`, `search`, `query` は Relay Connection 形式で返ります。

- ページング: `first` (デフォルト 20), `after` (Base64 `arrayconnection:{offset}`)
- 構造:
  ```
  {
    edges: [{ node: {...}, cursor: "..." }],
    pageInfo: { hasNextPage, hasPreviousPage, startCursor, endCursor },
    totalCount
  }
  ```
- `node` には `NodeMapper` の結果が入ります。
- `children` はもともと `NodeIterator` を直接ページングしていましたが、`references`/`xpath`/`search`/`query` も `PropertyIterator` / `NodeIterator` / `RowIterator` の `skip()` を使って `first` 件だけを逐次処理する実装に変わり、全件をリスト化せずにメモリフットプリントを抑えています。各 Iterator の `getSize()` は JCR 実装側でヒット件数を返すことを確認済みのため、`totalCount` は正確な値になります。

## Query オペレーション

GraphQL リクエストは 1 つのルートフィールドのみを想定しています (`GraphQLExecutor` がクエリ文字列のパターンにマッチした最初のオペレーションを実行するため)。

### node

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 絶対パス。GraphQL 変数 (`$path`) も利用可能。 |

返り値: `node` (存在しない場合は `null`)

例:

```graphql
{ node(path: "/content/page1") { path name nodeType created } }
```

### children

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 親ノードのパス |
| `first` | Int |   | 20 | 取得件数 |
| `after` | String |   | - | Base64 カーソル (`arrayconnection:{offset}`) |

返り値: `children` Connection。`edges.node` は子ノード、`totalCount` は子ノード数。`NodeMapper` のフィールド選択が尊重されます。

### references

指定ノードを参照しているノードを Connection 形式で返します (対象が `mix:referenceable` でない場合は空配列)。

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 参照されるノード |
| `first`, `after` | Int / String |   | 20 / - | ページング |

### accessControl

ACL を取得します。MintJams 拡張 ACL を使用している場合は `allow` が実際の許可/拒否を指します (JCR 標準 ACL の場合は常に `true`)。

| 引数 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |

返り値:

```graphql
{
  accessControl {
    entries { principal privileges allow }
  }
}
```

### versionHistory

`mix:versionable` ノードのバージョン履歴。

| 引数 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |

返り値には `versions[] { name created predecessors[] successors[] frozenNodePath }`、`baseVersion`, `versionableUuid` が含まれます。

### xpath

JCR XPath を実行します。

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `query` | String | ○ | - | XPath (例: `"//element(*, nt:file)"`) |
| `first`, `after` | Int / String |   | 20 / - | ページング |

返り値: `xpath` Connection。

### search

`jcr:contains` を使った全文検索。内部的には `jcr:contains` 付き XPath を発行し、`nt:file` のヒットのみを返します。各ノードに `score` が追加されます。

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `text` | String | ○ | - | 検索語 (`'` は自動エスケープ) |
| `path` | String |   | `/` | 検索ルート。`/content` など |
| `first`, `after` | Int / String |   | 20 / - | ページング |

レスポンス例:

```graphql
{
  search(text: "asset", path: "/content/assets", first: 5) {
    edges {
      node { path name score mimeType }
      cursor
    }
    pageInfo { hasNextPage endCursor }
    totalCount
  }
}
```

### query (generic)

任意の JCR クエリ (JCR-SQL2 / XPath / SQL) を実行します。戻り値のキーは `query` です。

| 引数 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `statement` | String | ○ | - | 実行するクエリ文 |
| `language` | String |   | `JCR-SQL2` | `JCR-SQL2`, `XPath`, `SQL` (大小文字は自動正規化) |
| `first`, `after` | Int / String |   | 20 / - | ページング |

例:

```graphql
{
  query(statement: "SELECT * FROM [nt:file] WHERE ISDESCENDANTNODE('/content')", language: "JCR-SQL2", first: 10) {
    edges { node { path name mimeType } cursor }
    pageInfo { hasNextPage }
    totalCount
  }
}
```

### camelContext

引数なし。現在のワークスペースにデプロイされているすべての Apache Camel コンテキストとルートの状態を返します。

返り値: `camelContext` (オブジェクトの配列)

| フィールド | 型 | 説明 |
| --- | --- | --- |
| `name` | String | CamelContext の名前 |
| `state` | String | コンテキストの状態 (`Started`, `Stopped`, `Starting`, `Stopping`, `Suspended`) |
| `sourceFile` | String | ルートが定義された JCR ノードのパス |
| `routes[]` | Array | ルートの一覧 |
| `routes[].id` | String | ルート ID |
| `routes[].status` | String | ルートの状態 (`Started`, `Stopped` 等) |
| `routes[].exchangesTotal` | Long | 処理済みの Exchange 総数 (JMX 無効時は `0`) |

例:

```graphql
query CheckCamelStatus {
  camelContext {
    name
    state
    sourceFile
    routes {
      id
      status
      exchangesTotal
    }
  }
}
```

レスポンス例:

```json
{
  "data": {
    "camelContext": [
      {
        "name": "camel-1",
        "state": "Started",
        "sourceFile": "/etc/eip/routes/my-route.yaml",
        "routes": [
          {
            "id": "my-route",
            "status": "Started",
            "exchangesTotal": 42
          }
        ]
      }
    ]
  }
}
```

ルートが一つもデプロイされていない場合は空配列 `[]` が返ります。

## Mutation オペレーション

ミューテーションは `mutation { ... }` で 1 フィールドずつ呼び出してください。`MutationExecutor` は `variables.input` もしくはクエリ文字列内の `input: { ... }` を解析します。

### createFolder

| 入力 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 親ノード |
| `name` | String | ○ | - | 作成するノード名 |
| `nodeType` | String |   | `nt:folder` | 必要に応じて上書き |
| `createParents` | Boolean |   | `true` | 親パスが存在しない場合、自動的に作成するか |

返り値: `createFolder` (作成済みノード)。`createParents`が`true`（デフォルト）の場合、親パスが存在しなければ中間フォルダーも自動的に作成されます。

### createFile

| 入力 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 親ノード |
| `name` | String | ○ | - | ファイル名 |
| `mimeType` | String |   | `application/octet-stream` | `jcr:content/jcr:mimeType` |
| `content` | String | ○ | - | Base64 データ |
| `nodeType` | String |   | `nt:file` | カスタムタイプを使う場合に指定 |
| `createParents` | Boolean |   | `true` | 親パスが存在しない場合、自動的に作成するか |

`jcr:content (nt:resource)` を自動生成し、`jcr:lastModified`/`jcr:lastModifiedBy` を現在のセッションで設定します。返り値: `createFile`.
`mimeType` を指定した場合は `jcr:content/jcr:mimeType` にセットされます。省略時は `application/octet-stream` がデフォルト値として使用されます。カスタムノードタイプを指定した場合は、`jcr:content` の構造が異なる可能性があるため、`mimeType` は無視されます。`createParents`が`true`（デフォルト）の場合、親パスが存在しなければ中間フォルダーも自動的に作成されます。

### deleteNode

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 削除対象 |

返り値: `deleteNode: Boolean` (存在しない場合は `false`)

### renameNode

ノードの名前を変更します。同一親内で新しい名前に移動することで名前変更を実現しています。

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 名前変更対象のノードパス |
| `name` | String | ○ | 新しい名前 |

返り値: `renameNode` (名前変更後のノード情報)。新しいパスでノードを取得できます。

例:

```graphql
mutation {
  renameNode(input: { path: "/content/oldname", name: "newname" }) {
    path
    name
  }
}
```

### moveNode

ノードを別の親ディレクトリに移動します。オプションで移動時に名前を変更することもできます。

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `sourcePath` | String | ○ | 移動元のノードパス |
| `destPath` | String | ○ | 移動先の親ノードパス |
| `name` | String |   | 移動先での新しい名前（省略時は元の名前を保持） |

返り値: `moveNode` (移動後のノード情報)。新しいパスでノードを取得できます。

例:

```graphql
mutation {
  moveNode(input: { sourcePath: "/content/file.txt", destPath: "/content/archive" }) {
    path
    name
  }
}
```

移動と同時に名前変更:

```graphql
mutation {
  moveNode(input: { sourcePath: "/content/file.txt", destPath: "/content/archive", name: "archived-file.txt" }) {
    path
    name
  }
}
```

### lockNode

| 入力 | 型 | 必須 | デフォルト | 説明 |
| --- | --- | --- | --- | --- |
| `path` | String | ○ | - | 対象ノード |
| `isDeep` | Boolean |   | `false` | 下位ノードもロックするか |
| `isSessionScoped` | Boolean |   | `false` | セッション限定ロック |

`mix:lockable` を自動付与します。返り値: `lockNode` (最新ノード情報)。

### unlockNode

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | ロック解除対象 |

返り値: `unlockNode: true`.

### setProperties

複数プロパティを一括更新します (すべて成功した場合のみ `session.save()`、失敗時は `session.refresh(false)` でロールバック)。

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |
| `properties` | [PropertyInput] | ○ | `{ name, value: PropertyValueInput }` の配列 |

返り値:

```graphql
{
  setProperties {
    node { ... }      # 更新後ノード
    errors [{ message }]
  }
}
```

### addMixin / deleteMixin

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |
| `mixinType` | String | ○ | 追加/除去する mixin 名 |

返り値: `addMixin` / `deleteMixin` (ノード情報)。

### setAccessControl

1 つのエントリ、または `entries` 配列で複数エントリをまとめて設定できます。既存エントリは principal 単位で置き換えられます。

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | ACL を設定するノード |
| `principal` | String | △ | 単一モードで使用 |
| `privileges` | [String] | △ | 〃 (`["jcr:read", ...]`) |
| `allow` | Boolean |   | 省略時は `true`。MintJams 拡張 ACL のみ有効 |
| `entries` | [AccessControlEntryInput] | △ | 複数エントリを一括設定 (`principal`, `privileges`, `allow`) |

返り値: `setAccessControl { entries[] }`.

### deleteAccessControl

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |
| `principal` | String | ○ | 削除する principal |

返り値: `deleteAccessControl: true`.

### checkin / checkout

バージョン管理を行います (対象ノードは `mix:versionable` 必須)。

- `checkin(path: "/content/page1")` → `checkin { name created }`
- `checkout(path: "/content/page1")` → `checkout: true`

### restoreVersion

| 入力 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `path` | String | ○ | 対象ノード |
| `versionName` | String | ○ | `1.0` など Version 名 |

未チェックアウトの場合は自動で `checkout` してからリストアします。返り値: `restoreVersion` (最新ノード)。

## 制限事項・メモ

- 現状は **1 リクエスト 1 ルートフィールド** を前提にしています。複数フィールドを並べても、最初にマッチしたものしか実行されません。
- AST パーサーは Operation 名や Fragments には対応していません。SelectionSet は 1 階層のみ解析してフィールド最適化に利用します。
- Connection 系クエリ (`children`/`references`/`xpath`/`search`/`query`) は JCR の Iterator/RowIterator を `skip()` しながらストリーミングします。ページング対象以外を保持しないためメモリ使用は `first` 件分に限定されます。対象の JCR 実装では `NodeIterator#getSize()` / `RowIterator#getSize()` / `PropertyIterator#getSize()` がヒット件数を返すことを確認済みのため、`totalCount` は常に実際の件数になります。
- `search` は全文検索専用で `nt:file` に固定されています。レリバンススコア順で結果が返されます。
  複合条件や任意の並べ替えが必要な場合は `query` クエリまたは `xpath` クエリを使用してください。
- `createFile` / `PropertyValueInput` のバイナリは Base64、日付は ISO-8601 (UTC) で渡してください。
