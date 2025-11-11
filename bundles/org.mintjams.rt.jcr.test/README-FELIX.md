# JCR 2.0 自動テストバンドル（Apache Felix版）

このバンドルは、Apache Felix OSGiコンテナで自動的にJCRテストを実行し、結果をファイルに出力します。

## 特徴

- ✅ バンドルをデプロイして起動すると自動的にテスト実行
- ✅ テスト結果をテキストファイルとHTMLファイルに出力
- ✅ コンソールにもリアルタイムで結果を表示
- ✅ CIパイプラインに組み込み可能
- ✅ 手動でのテスト実行不要

## デプロイ方法

### 1. バンドルのビルド

Eclipse IDEでプロジェクトを右クリック → Export → Plug-in Development → Deployable plug-ins and fragments

または、コマンドラインから：

```bash
cd org.mintjams.rt.jcr.test
# JARファイルを作成
jar cvfm org.mintjams.rt.jcr.test_1.0.0.jar META-INF/MANIFEST.MF -C bin .
```

### 2. Apache Felixへのデプロイ

ビルドしたJARファイルを、Felixの`bundle`ディレクトリにコピーします：

```bash
# 例：Felixのインストールディレクトリが /opt/felix の場合
cp org.mintjams.rt.jcr.test_1.0.0.jar /opt/felix/bundle/
```

### 3. 必要な依存バンドルの確認

以下のバンドルがインストールされている必要があります：

- `org.mintjams.rt.jcr` (テスト対象のJCR実装)
- `javax.jcr` (JCR API)
- `org.junit` (JUnit 4.12以上)
- OSGi Declarative Services実装 (Felix SCRなど)
- その他の依存バンドル（HikariCP, H2, SnakeYAML等）

### 4. Felixの起動

```bash
cd /opt/felix
java -jar bin/felix.jar
```

バンドルが自動的に起動し、約2秒後にテストが実行されます。

## テスト結果の確認

### コンソール出力

テスト実行中、コンソールには以下のような出力が表示されます：

```
========================================
Starting JCR Tests
========================================

========== Test Results ==========
Tests run: 26
Tests passed: 26
Tests failed: 0
Tests ignored: 0
Time elapsed: 1234ms
Success: true
==================================

Test results written to: test-results/jcr-test-results-20251111-010823.txt
HTML report written to: test-results/jcr-test-results-20251111-010823.html
========================================
JCR Tests Completed
========================================
```

### ファイル出力

テスト結果は以下のディレクトリに出力されます：

**デフォルト:** `test-results/`

出力ファイル：
- `jcr-test-results-YYYYMMDD-HHmmss.txt` - テキスト形式のレポート
- `jcr-test-results-YYYYMMDD-HHmmss.html` - HTML形式のレポート（ブラウザで表示可能）

### 出力ディレクトリのカスタマイズ

システムプロパティで出力ディレクトリを変更できます：

```bash
java -Djcr.test.output.dir=/var/log/jcr-tests -jar bin/felix.jar
```

## HTML レポートの表示

生成されたHTMLファイルをブラウザで開くと、見やすい形式でテスト結果を確認できます：

- テスト実行サマリー（実行数、成功数、失敗数）
- 各テストの詳細
- 失敗したテストのスタックトレース（展開可能）
- 実行時間

## トラブルシューティング

### テストが実行されない

1. **Repositoryサービスが利用できない**

   Felix コンソールで確認：
   ```
   g! lb
   ```
   `org.mintjams.rt.jcr` バンドルがACTIVE状態であることを確認してください。

2. **Declarative Servicesが動作していない**

   Felix SCR (Service Component Runtime) がインストールされていることを確認：
   ```
   g! lb
   ```
   `org.apache.felix.scr` がACTIVE状態であることを確認してください。

3. **JUnitバンドルが見つからない**

   JUnit 4.12以上がインストールされていることを確認してください。

### 出力ファイルが生成されない

1. **書き込み権限を確認**

   出力ディレクトリへの書き込み権限があることを確認してください。

2. **出力ディレクトリのパス**

   相対パスの場合、Felixの実行ディレクトリからの相対パスになります。
   絶対パスを指定することをお勧めします。

### テストがスキップされる

テストメソッドが実行されずにスキップされる場合：

1. JCRリポジトリサービスが正しく起動しているか確認
2. セッションの作成に失敗していないか、コンソールログを確認
3. 依存バンドルがすべてインストールされているか確認

## CI/CDパイプラインへの統合

### 例：Jenkins

```groovy
pipeline {
    stage('Deploy and Test') {
        steps {
            // バンドルをコピー
            sh 'cp target/org.mintjams.rt.jcr.test_1.0.0.jar /opt/felix/bundle/'

            // Felixを起動（バックグラウンド）
            sh 'cd /opt/felix && java -jar bin/felix.jar > felix.log 2>&1 &'

            // テスト完了を待つ
            sh 'sleep 10'

            // テスト結果を確認
            sh 'cat test-results/jcr-test-results-*.txt'

            // HTMLレポートをアーカイブ
            archiveArtifacts 'test-results/*.html'
        }
    }
}
```

### 例：シェルスクリプト

```bash
#!/bin/bash

# バンドルをデプロイ
cp org.mintjams.rt.jcr.test_1.0.0.jar /opt/felix/bundle/

# Felixを起動
cd /opt/felix
java -Djcr.test.output.dir=/tmp/jcr-tests -jar bin/felix.jar > /tmp/felix.log 2>&1 &
FELIX_PID=$!

# テスト完了を待つ
sleep 10

# テスト結果をチェック
if grep -q "Success: true" /tmp/jcr-tests/jcr-test-results-*.txt; then
    echo "All tests passed!"
    exit 0
else
    echo "Tests failed!"
    cat /tmp/jcr-tests/jcr-test-results-*.txt
    exit 1
fi

# Felixを停止
kill $FELIX_PID
```

## 実行タイミングのカスタマイズ

デフォルトでは、バンドル起動から2秒後にテストが実行されます。
これを変更したい場合は、`TestRunner.java` の以下の部分を編集してください：

```java
// Wait a bit for all services to be available
Thread.sleep(2000);  // <- この値を変更
```

## テストクラスの追加

新しいテストクラスを追加する場合は、`TestRunner.java` の以下の配列に追加してください：

```java
Class<?>[] testClasses = {
    JcrSessionTest.class,
    JcrRepositoryTest.class,
    JcrNamespaceRegistryTest.class,
    // 新しいテストクラスをここに追加
};
```

## ファイル構成

```
org.mintjams.rt.jcr.test/
├── META-INF/
│   └── MANIFEST.MF              # バンドル設定
├── OSGI-INF/
│   └── *.xml                    # Declarative Services設定
├── src/org/mintjams/rt/jcr/internal/
│   ├── Activator.java           # バンドルアクティベーター
│   ├── TestRunner.java          # 自動テスト実行サービス
│   ├── AbstractOSGiTest.java    # テスト基底クラス
│   ├── JcrSessionTest.java      # セッションテスト
│   ├── JcrRepositoryTest.java   # リポジトリテスト
│   └── JcrNamespaceRegistryTest.java # 名前空間テスト
├── build.properties
└── README-FELIX.md              # このファイル
```

## まとめ

このテストバンドルを使用することで：

1. ✅ **自動化** - デプロイするだけでテスト実行
2. ✅ **可視化** - HTMLレポートで結果を確認
3. ✅ **統合** - CI/CDパイプラインに簡単に組み込み
4. ✅ **信頼性** - 実際のOSGi環境で実行され、モックではない実装をテスト

これにより、手動テストの負担を減らしつつ、品質を維持できます。
