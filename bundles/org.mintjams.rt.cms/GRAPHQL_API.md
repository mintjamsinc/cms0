# GraphQL API for cms0

cms0のコンテンツ管理用GraphQL APIの実装ドキュメント

## エンドポイント

```
POST /bin/graphql.cgi/{workspace}
GET  /bin/graphql.cgi/{workspace}
```

例：
- `http://localhost:8080/bin/graphql.cgi/system`
- `http://localhost:8080/bin/graphql.cgi/web`

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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ node(path: \"/content/page1\") { path name nodeType } }"
  }'
```

変数を使用する場合：

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

#### 子ノード一覧取得

GraphQL Relay Connection仕様に準拠したカーソルベースページネーションを使用します。

```graphql
{
  children(path: "/content", first: 10, after: "cursor") {
    edges {
      node {
        path
        name
        nodeType
        created
      }
      cursor
    }
    pageInfo {
      hasNextPage
      hasPreviousPage
      startCursor
      endCursor
    }
    totalCount
  }
}
```

POSTリクエスト例：

```bash
# 最初のページ（first: 10件）
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ children(path: \"/content\", first: 10) { edges { node { path name } cursor } pageInfo { hasNextPage endCursor } totalCount } }"
  }'

# 次のページ（afterにendCursorを指定）
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ children(path: \"/content\", first: 10, after: \"YXJyYXljb25uZWN0aW9uOjk=\") { edges { node { path name } cursor } pageInfo { hasNextPage endCursor } } }"
  }'
```

パラメータ：
- `path`: 親ノードのパス（必須）
- `first`: 取得する件数（デフォルト: 20）
- `after`: カーソル（前回のendCursorを指定）

レスポンス例：

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
        },
        {
          "node": {
            "path": "/content/page2",
            "name": "page2"
          },
          "cursor": "YXJyYXljb25uZWN0aW9uOjE="
        }
      ],
      "pageInfo": {
        "hasNextPage": true,
        "hasPreviousPage": false,
        "startCursor": "YXJyYXljb25uZWN0aW9uOjA=",
        "endCursor": "YXJyYXljb25uZWN0aW9uOjE="
      },
      "totalCount": 50
    }
  }
}
```

**注意**:
- `cursor`はBase64エンコードされた位置情報です
- 次のページを取得するには、`pageInfo.endCursor`を`after`パラメータに指定します
- `pageInfo.hasNextPage`が`false`になるまでページングを続けます

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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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

curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { addMixin(input: { path: \"/content/target\", mixinType: \"mix:referenceable\" }) { path uuid } }"
  }'
```

#### Mixinタイプ削除

```graphql
mutation {
  deleteMixin(input: {
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { deleteMixin(input: { path: \"/content/target\", mixinType: \"mix:referenceable\" }) { path } }"
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
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

## バージョン管理 (Versioning)

JCRのバージョン管理機能を使用して、ノードの変更履歴を管理します。

### 前提条件

バージョン管理を使用するには、ノードに`mix:versionable` mixinを追加する必要があります。

```graphql
mutation {
  addMixin(input: {
    path: "/content/page1"
    mixinType: "mix:versionable"
  }) {
    path
  }
}
```

### バージョン履歴取得

指定したノードのバージョン履歴を取得します。

```graphql
{
  versionHistory(path: "/content/page1") {
    versions {
      name
      created
      predecessors
      successors
    }
    baseVersion
    versionableUuid
  }
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ versionHistory(path: \"/content/page1\") { versions { name created } baseVersion } }"
  }'
```

レスポンス例：

```json
{
  "data": {
    "versionHistory": {
      "versions": [
        {
          "name": "1.0",
          "created": "2024-01-15T10:30:00.000Z"
        },
        {
          "name": "1.1",
          "created": "2024-01-16T14:20:00.000Z",
          "predecessors": ["1.0"]
        }
      ],
      "baseVersion": "1.1",
      "versionableUuid": "123e4567-e89b-12d3-a456-426614174000"
    }
  }
}
```

### チェックイン (新しいバージョンの作成)

ノードをチェックインして新しいバージョンを作成します。チェックイン後、ノードは読み取り専用になります。

```graphql
mutation {
  checkin(path: "/content/page1")
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { checkin(path: \"/content/page1\") }"
  }'
```

レスポンス例：

```json
{
  "data": {
    "checkin": {
      "name": "1.2",
      "created": "2024-01-17T09:15:00.000Z"
    }
  }
}
```

**注意**: チェックインすると、ノードは読み取り専用になります。編集するにはチェックアウトが必要です。

### チェックアウト (編集のためのロック解除)

ノードをチェックアウトして編集可能な状態にします。

```graphql
mutation {
  checkout(path: "/content/page1")
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { checkout(path: \"/content/page1\") }"
  }'
```

**ワークフロー例**:
1. ノードをチェックアウト (`checkout`)
2. プロパティを編集 (`setProperty`)
3. ノードをチェックイン (`checkin`) - 新しいバージョンが作成される

### バージョン復元

ノードを特定のバージョンに復元します。

```graphql
mutation {
  restoreVersion(input: {
    path: "/content/page1"
    versionName: "1.0"
  }) {
    path
    name
  }
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { restoreVersion(input: { path: \"/content/page1\", versionName: \"1.0\" }) { path } }"
  }'
```

パラメータ：
- `path`: 復元するノードのパス（必須）
- `versionName`: 復元するバージョン名（例: "1.0", "1.1"）（必須）

**注意**:
- 復元すると、現在の内容が指定したバージョンの内容で置き換えられます
- ノードがチェックインされている場合は、自動的にチェックアウトされます

### バージョン管理の使用例

```bash
# 1. mix:versionableを追加
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { addMixin(input: { path: \"/content/doc\", mixinType: \"mix:versionable\" }) { path } }"
  }'

# 2. 初回チェックイン（バージョン1.0を作成）
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { checkin(path: \"/content/doc\") }"
  }'

# 3. 編集のためにチェックアウト
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { checkout(path: \"/content/doc\") }"
  }'

# 4. プロパティを変更
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { setProperty(input: { path: \"/content/doc\", name: \"title\", value: \"Updated Title\" }) { path } }"
  }'

# 5. 変更を保存（バージョン1.1を作成）
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { checkin(path: \"/content/doc\") }"
  }'

# 6. バージョン履歴を確認
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ versionHistory(path: \"/content/doc\") { versions { name created } baseVersion } }"
  }'

# 7. 以前のバージョンに戻す
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { restoreVersion(input: { path: \"/content/doc\", versionName: \"1.0\" }) { path } }"
  }'
```

## アクセス権限管理 (ACL)

### ACL取得

指定したノードのアクセス制御エントリ一覧を取得します。

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

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ accessControl(path: \"/content/page1\") { entries { principal privileges allow } } }"
  }'
```

レスポンス例：

```json
{
  "data": {
    "accessControl": {
      "entries": [
        {
          "principal": "admin",
          "privileges": ["jcr:all"],
          "allow": true
        },
        {
          "principal": "user1",
          "privileges": ["jcr:read", "jcr:write"],
          "allow": true
        }
      ]
    }
  }
}
```

### ACLエントリ設定

指定したプリンシパル（ユーザーまたはグループ）のACLエントリを設定・更新します。

```graphql
mutation {
  setAccessControl(input: {
    path: "/content/page1"
    principal: "user1"
    privileges: ["jcr:read", "jcr:write"]
    allow: true
  }) {
    entries {
      principal
      privileges
      allow
    }
  }
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { setAccessControl(input: { path: \"/content/page1\", principal: \"user1\", privileges: [\"jcr:read\", \"jcr:write\"], allow: true }) { entries { principal privileges } } }"
  }'
```

パラメータ：
- `path`: ACLを設定するノードのパス（必須）
- `principal`: プリンシパル名（ユーザーIDまたはグループ名）（必須）
- `privileges`: 権限の配列（必須）
  - `jcr:read` - 読み取り権限
  - `jcr:write` - 書き込み権限
  - `jcr:modifyProperties` - プロパティ変更権限
  - `jcr:addChildNodes` - 子ノード追加権限
  - `jcr:removeNode` - ノード削除権限
  - `jcr:removeChildNodes` - 子ノード削除権限
  - `jcr:readAccessControl` - ACL読み取り権限
  - `jcr:modifyAccessControl` - ACL変更権限
  - `jcr:lockManagement` - ロック管理権限
  - `jcr:versionManagement` - バージョン管理権限
  - `jcr:all` - すべての権限
- `allow`: 許可(true)または拒否(false)（デフォルト: true）

**注意**: 同じプリンシパルの既存エントリは削除され、新しいエントリで置き換えられます。

### ACLエントリ削除

指定したプリンシパルのACLエントリを削除します。

```graphql
mutation {
  deleteAccessControl(input: {
    path: "/content/page1"
    principal: "user1"
  })
}
```

POSTリクエスト例：

```bash
curl -X POST http://localhost:8080/bin/graphql.cgi/web \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { deleteAccessControl(input: { path: \"/content/page1\", principal: \"user1\" }) }"
  }'
```

パラメータ：
- `path`: ACLを削除するノードのパス（必須）
- `principal`: 削除するプリンシパル名（必須）

## Phase 2以降の予定機能

- XPath検索
- プロパティ管理（カスタムプロパティの高度な取得）
- フルテキスト検索
- より高度なGraphQLクエリパース（フィールド選択など）

## 実装済み機能

### Phase 1
- ノード取得 (node query)
- 子ノード一覧取得 (children query with Relay Connection pagination)
- フォルダ作成 (createFolder mutation)
- ファイル作成 (createFile mutation)
- ノード削除 (deleteNode mutation)
- プロパティ削除 (deleteProperty mutation)
- ノードロック/ロック解除 (lockNode/unlockNode mutation)
- 参照管理 (mix:referenceable サポート)
  - Mixin追加・削除 (addMixin/deleteMixin mutation)
  - プロパティ設定 (setProperty mutation with Reference/WeakReference)
  - 参照元ノード取得 (references query)
  - UUID取得 (uuid field)
- アクセス権限管理 (ACL操作)
  - ACL取得 (accessControl query)
  - ACLエントリ設定 (setAccessControl mutation)
  - ACLエントリ削除 (deleteAccessControl mutation)
- バージョン管理 (Versioning)
  - バージョン履歴取得 (versionHistory query)
  - チェックイン (checkin mutation)
  - チェックアウト (checkout mutation)
  - バージョン復元 (restoreVersion mutation)

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
