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

## ノードタイプ別のフィールド

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
- プロパティ管理（カスタムプロパティの取得・設定）
- アクセス権限管理
- ロック管理
- 参照可能（mix:referenceable）管理
- フルテキスト検索
- バージョニング
- より高度なGraphQLクエリパース（フィールド選択など）

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
