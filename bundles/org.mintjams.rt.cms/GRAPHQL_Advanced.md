# GraphQL Implementation with Advanced Parsing and Field Selection Optimization

## 概要

このGraphQL実装は、JCRリポジトリに対するクエリを効率的に実行するための高度なパースと**フィールド選択の最適化**機能を提供します。

## 主要な機能

### 1. 高度なGraphQLパース (AST)

クエリを抽象構文木(AST)として解析し、以下をサポート:
- フィールド選択の詳細な解析
- ネストされたフィールド
- エイリアス
- 引数
- 変数

### 2. フィールド選択の最適化

クエリで要求されたフィールドのみをマッピングすることで、パフォーマンスを最適化:

**従来の方法** (すべてのフィールドを取得):
```graphql
{
  node(path: "/content/page1") {
    name
    path
  }
}
```

従来は、`name`と`path`のみが必要でも、`properties`, `lockInfo`, `mimeType`, `size`などすべてのフィールドを取得していました。

**最適化後**:
要求された`name`と`path`のみを取得し、不要な`properties`の列挙やロック情報の取得をスキップします。

### 3. パフォーマンスの向上

特に大量のノードを扱う場合に効果的:

```graphql
{
  children(path: "/content", first: 100) {
    edges {
      node {
        name
        path
      }
    }
    pageInfo {
      hasNextPage
    }
  }
}
```

この場合、100ノード×各フィールドの最適化により、大幅なパフォーマンス向上が期待できます。

## 使用例

### 基本的なノードクエリ

```graphql
{
  node(path: "/content/page1") {
    name
    path
    nodeType
    created
    createdBy
  }
}
```

### プロパティのネスト選択

```graphql
{
  node(path: "/content/page1") {
    name
    properties {
      name
      type
      value
    }
  }
}
```

プロパティが選択されていない場合、PropertyIteratorの列挙はスキップされます。

### 子ノードのページネーション

```graphql
{
  children(path: "/content", first: 20, after: "cursor") {
    edges {
      node {
        name
        path
        modified
      }
      cursor
    }
    pageInfo {
      hasNextPage
      endCursor
    }
    totalCount
  }
}
```

各子ノードは`name`, `path`, `modified`のみを取得します。

### フルテキスト検索

```graphql
{
  search(text: "hello world", path: "/content", first: 20) {
    edges {
      node {
        path
        name
        nodeType
        score
      }
      cursor
    }
    pageInfo {
      hasNextPage
    }
    totalCount
  }
}
```

検索結果のノードも選択されたフィールドのみを含みます。

### XPath/SQL2クエリ

```graphql
{
  xpath(query: "//element(*, nt:file)", language: "xpath", first: 10) {
    edges {
      node {
        name
        mimeType
        size
      }
    }
    totalCount
  }
}
```

## 実装の詳細

### アーキテクチャ

```
GraphQLRequest
    ↓
GraphQLParser (AST生成)
    ↓
Operation → Field → SelectionSet
    ↓
QueryExecutor (クエリ実行)
    ↓
NodeMapper (最適化されたフィールドマッピング)
    ↓
GraphQLResponse
```

### 主要なクラス

#### `org.mintjams.rt.cms.internal.graphql.ast.GraphQLParser`
- GraphQLクエリを解析してASTを生成
- トークン化、構文解析、変数解決

#### `org.mintjams.rt.cms.internal.graphql.ast.SelectionSet`
- フィールド選択を表現
- ネストされたフィールドのナビゲーション
- `hasField()`メソッドで効率的なフィールドチェック

#### `org.mintjams.rt.cms.internal.graphql.NodeMapper`
- JCRノードをGraphQL形式にマッピング
- **SelectionSetを利用した条件付きマッピング**
- 不要なフィールドの取得をスキップ

#### `org.mintjams.rt.cms.internal.graphql.QueryExecutor`
- 各クエリタイプの実行
- フィールド選択情報をNodeMapperに渡す

## パフォーマンスの考慮事項

### 最適化されるフィールド

以下のフィールドは要求された場合のみ処理されます:

1. **Properties** - PropertyIteratorの列挙 (コストが高い)
2. **Lock情報** - LockManagerへのアクセス (コストが高い)
3. **File specific fields** - jcr:contentノードへのアクセス
4. **Folder specific fields** - hasNodes()チェック (大きなフォルダで遅い)

### 使用上のヒント

1. **必要なフィールドのみを選択**
   ```graphql
   # 良い例
   { node(path: "/content") { name path } }

   # 悪い例 (すべてのフィールド)
   { node(path: "/content") { name path properties { name value } lockOwner isLocked ... } }
   ```

2. **大量データの処理時はページネーションを使用**
   ```graphql
   children(path: "/content", first: 50, after: "cursor")
   ```

3. **プロパティは必要な時だけ取得**
   ```graphql
   properties { name value }  # typeが不要ならname, valueのみ
   ```

## 後方互換性

既存のコードは変更なしで動作します:
- `NodeMapper.toGraphQL(node)` - すべてのフィールドを含む (従来の動作)
- `NodeMapper.toGraphQL(node, selectionSet)` - 最適化された新しいメソッド

## 今後の改善

1. フラグメントのサポート
2. ディレクティブ (@include, @skip)
3. より詳細なクエリ分析とキャッシング
4. バッチ処理の最適化
