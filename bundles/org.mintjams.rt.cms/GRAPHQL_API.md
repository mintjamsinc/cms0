# GraphQL API for cms0

cms0のコンテンツ管理用GraphQL APIの実装ドキュメント

## エンドポイント

```
POST /bin/graphql.cgi/{workspace}
GET  /bin/graphql.cgi/{workspace}
```

例：
- `https://d701p.mintjams.jp/bin/graphql.cgi/system`
- `https://d701p.mintjams.jp/bin/graphql.cgi/web`

## 認証

cms0の既存認証機構を使用します。ログイン済みのユーザーのみアクセス可能です。

## Phase 1: 基本機能

### Query操作

#### ノード取得

```graphql
{
  node(path: "/content/page1") {
    path
    name
    nodeType
    created
    createdBy
    modified
    modifiedBy
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ node(path: \"/content/page1\") { path name nodeType } }"
  }'
```

変数を使用する場合：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query GetNode($path: String!) { node(path: $path) { path name } }",
    "variables": {
      "path": "/content/page1"
    }
  }'
```

#### 子ノード一覧取得

```graphql
{
  children(path: "/content", limit: 10, offset: 0) {
    nodes {
      path
      name
      nodeType
      created
    }
    totalCount
    pageInfo {
      hasNextPage
      hasPreviousPage
    }
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ children(path: \"/content\", limit: 10) { nodes { path name } totalCount } }"
  }'
```

### Mutation操作

#### フォルダ作成

```graphql
mutation {
  createFolder(input: {
    path: "/content"
    name: "newfolder"
    nodeType: "nt:folder"
  }) {
    path
    name
    created
    createdBy
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation CreateFolder($input: CreateFolderInput!) { createFolder(input: $input) { path name } }",
    "variables": {
      "input": {
        "path": "/content",
        "name": "newfolder"
      }
    }
  }'
```

#### ファイル作成

```graphql
mutation {
  createFile(input: {
    path: "/content"
    name: "test.txt"
    mimeType: "text/plain"
    content: "SGVsbG8gV29ybGQh"  # Base64エンコード
  }) {
    path
    name
    mimeType
    size
  }
}
```

POSTリクエスト例：

```bash
# contentはBase64エンコード
echo -n "Hello World!" | base64  # SGVsbG8gV29ybGQh

curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation CreateFile($input: CreateFileInput!) { createFile(input: $input) { path name size } }",
    "variables": {
      "input": {
        "path": "/content",
        "name": "test.txt",
        "mimeType": "text/plain",
        "content": "SGVsbG8gV29ybGQh"
      }
    }
  }'
```

#### ノード削除

```graphql
mutation {
  deleteNode(path: "/content/page1")
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { deleteNode(path: \"/content/page1\") }"
  }'
```

## レスポンス形式

### 成功時

```json
{
  "data": {
    "node": {
      "path": "/content/page1",
      "name": "page1",
      "nodeType": "nt:file"
    }
  }
}
```

### エラー時

```json
{
  "errors": [
    {
      "message": "Node not found: /content/invalid",
      "extensions": {
        "exception": "javax.jcr.PathNotFoundException"
      }
    }
  ]
}
```

#### ノードのロック

```graphql
mutation {
  lockNode(input: {
    path: "/content/page1"
    isDeep: false
    isSessionScoped: false
  }) {
    path
    name
    isLocked
    lockOwner
    isDeep
    isSessionScoped
    isLockOwningSession
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { lockNode(input: { path: \"/content/page1\", isDeep: false, isSessionScoped: false }) { path isLocked lockOwner } }"
  }'
```

パラメータ：
- `path`: ロックするノードのパス（必須）
- `isDeep`: 子ノードも含めてロックするか（デフォルト: false）
- `isSessionScoped`: セッションスコープのロックか（デフォルト: false）
  - `false`: リクエスト間でロックを保持（推奨）
  - `true`: 現在のJCRセッション内でのみ有効（リクエスト終了時に自動解除）

**注意**: GraphQL APIでは各リクエスト終了時にJCRセッションが閉じられるため、`isSessionScoped=true`を設定すると次のリクエストでロックが解除されます。リクエスト間でロックを保持する場合は`isSessionScoped=false`（デフォルト）を使用してください。

#### ノードのロック解除

```graphql
mutation {
  unlockNode(path: "/content/page1")
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { unlockNode(path: \"/content/page1\") }"
  }'
```

## 参照管理 (mix:referenceable)

### Mixin追加・削除

#### Mixinタイプ追加

```graphql
mutation {
  addMixin(input: {
    path: "/content/target"
    mixinType: "mix:referenceable"
  }) {
    path
    uuid
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { addMixin(input: { path: \"/content/target\", mixinType: \"mix:referenceable\" }) { path uuid } }"
  }'
```

#### Mixinタイプ削除

```graphql
mutation {
  removeMixin(input: {
    path: "/content/target"
    mixinType: "mix:referenceable"
  }) {
    path
    uuid
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { removeMixin(input: { path: \"/content/target\", mixinType: \"mix:referenceable\" }) { path } }"
  }'
```

### プロパティ設定

#### 参照プロパティの設定

```graphql
mutation {
  setProperty(input: {
    path: "/content/source"
    name: "myReference"
    value: "uuid-of-target-node"
    type: "Reference"
  }) {
    path
    properties {
      name
      type
      value
    }
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { setProperty(input: { path: \"/content/source\", name: \"myRef\", value: \"123e4567-e89b-12d3-a456-426614174000\", type: \"Reference\" }) { path } }"
  }'
```

#### 弱参照プロパティの設定

```graphql
mutation {
  setProperty(input: {
    path: "/content/source"
    name: "myWeakRef"
    value: "uuid-of-target-node"
    type: "WeakReference"
  }) {
    path
  }
}
```

サポートされているプロパティタイプ：
- `String` (デフォルト)
- `Boolean`
- `Long`
- `Double`
- `Reference` - 強参照（参照先ノードの削除を防ぐ）
- `WeakReference` - 弱参照（参照先ノードの削除を許可）
- `Date`
- `Binary`
- `Path`
- `Name`
- `URI`
- `Decimal`

**注意**: `Reference`と`WeakReference`では、`value`にはターゲットノードのUUID（`mix:referenceable`ノードの識別子）を指定します。

### 参照元ノード取得

指定したノードを参照しているノード一覧を取得します。

```graphql
{
  references(path: "/content/target") {
    nodes {
      path
      name
      properties {
        name
        type
        value
      }
    }
    totalCount
  }
}
```

POSTリクエスト例：

```bash
curl -X POST https://d701p.mintjams.jp/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ references(path: \"/content/target\") { nodes { path name } totalCount } }"
  }'
```

**注意**: `references`クエリは、ターゲットノードに`mix:referenceable` mixinが追加されている場合のみ機能します。mixinがない場合は空のリストが返されます。

### UUIDの取得

`mix:referenceable`が追加されたノードは、`uuid`フィールドでUUIDを取得できます。

```graphql
{
  node(path: "/content/target") {
    path
    name
    uuid
  }
}
```

## ノードタイプ別のフィールド

### 共通フィールド（全ノードタイプ）

```
- path: String!
- name: String!
- nodeType: String!
- created: DateTime!
- createdBy: String!
- uuid: String                    # mix:referenceable の UUID（nullの場合もあり）
- isLocked: Boolean!
- lockOwner: String
- isDeep: Boolean!
- isSessionScoped: Boolean!
- isLockOwningSession: Boolean!
```

### nt:file（ファイル）

```
- path: String!
- name: String!
- nodeType: String!
- created: DateTime!
- createdBy: String!
- modified: DateTime!          # jcr:contentのjcr:lastModified
- modifiedBy: String!          # jcr:contentのjcr:lastModifiedBy
- mimeType: String!
- size: Long!
- encoding: String
- downloadUrl: String!
- isLocked: Boolean!
- lockOwner: String
- isDeep: Boolean!
- isSessionScoped: Boolean!
- isLockOwningSession: Boolean!
```

### nt:folder（フォルダ）

```
- path: String!
- name: String!
- nodeType: String!
- created: DateTime!
- createdBy: String!
- modified: DateTime!
- modifiedBy: String!
- hasChildren: Boolean!
- isLocked: Boolean!
- lockOwner: String
- isDeep: Boolean!
- isSessionScoped: Boolean!
- isLockOwningSession: Boolean!
```

## 実装クラス

### パッケージ構造

```
org.mintjams.rt.cms.internal.graphql/
├── GraphQLRequest.java           # リクエスト表現
├── GraphQLResponse.java          # レスポンス表現
├── GraphQLRequestParser.java    # リクエストパーサー
├── GraphQLExecutor.java          # メイン実行クラス
├── QueryExecutor.java            # Query操作
├── MutationExecutor.java         # Mutation操作
└── NodeMapper.java               # JCR→GraphQL変換

org.mintjams.rt.cms.internal.web/
└── GraphQLServlet.java           # Servletエンドポイント
```

## Phase 2以降の予定機能

- XPath検索
- プロパティ管理（カスタムプロパティの高度な取得）
- アクセス権限管理
- フルテキスト検索
- バージョニング
- より高度なGraphQLクエリパース（フィールド選択など）

## 実装済み機能

### Phase 1
- ノード取得 (node query)
- 子ノード一覧取得 (children query)
- フォルダ作成 (createFolder mutation)
- ファイル作成 (createFile mutation)
- ノード削除 (deleteNode mutation)
- ノードロック/ロック解除 (lockNode/unlockNode mutation)
- 参照管理 (mix:referenceable サポート)
  - Mixin追加・削除 (addMixin/removeMixin mutation)
  - プロパティ設定 (setProperty mutation with Reference/WeakReference)
  - 参照元ノード取得 (references query)
  - UUID取得 (uuid field)

## 開発メモ

### アーキテクチャ

- GraphQL Javaライブラリは使用せず、手動実装
- OSGiクラスローダー問題を回避
- 外部依存を最小限に
- Phase 1では基本的なCRUD操作のみ実装
- シンプルな正規表現ベースのクエリパース

### JCRプロパティの配置

JCR 2.0標準に準拠：

- `jcr:created`, `jcr:createdBy` → nt:fileノード自体
- `jcr:lastModified`, `jcr:lastModifiedBy` → jcr:contentノード

### 認証

- HttpServletRequestの属性から認証情報を取得
- セッションから認証情報を取得
- デフォルトはGuestCredentials

## テスト

```bash
# ノード取得テスト
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{"query": "{ node(path: \"/content\") { path name } }"}'

# フォルダ作成テスト
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { createFolder(input: { path: \"/content\", name: \"test\" }) { path } }",
    "variables": {
      "input": {
        "path": "/content",
        "name": "test"
      }
    }
  }'
```

## ビルド

Eclipse PDEでビルド：

1. プロジェクトを右クリック
2. "Export" → "Plug-in Development" → "Deployable plug-ins and fragments"
3. バンドルをエクスポート
