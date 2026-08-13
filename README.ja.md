# P2P Device Audio Relay

[English](README.md) | 日本語

同じローカルネットワーク上の2台の端末間で、デバイス音声を直接送るクロスプラットフォームアプリです。Android、iOS、Windows向けのネイティブアプリを含み、メディア中継サーバーやシグナリングサーバーを必要としません。

## 主な特徴

- LAN内での1対1音声セッション
- Android、iOS、Windowsに対応
- 別経路で受け渡すペアリング情報を利用
- ローカルIP接続としてUSBテザリングにも対応
- プロジェクト独自の中継、シグナリング、解析、アカウントサービスを使用しない

## 重要な制限

- ローカルネットワーク上の接続候補を使うため、LAN内での利用を前提としています。
- iOSで取得できるのはReplayKitが提供する音声であり、すべてのシステム音声を取得できるわけではありません。
- 対応する端末の組み合わせは [テストマトリクス](docs/TEST_MATRIX.md) で確認してください。
- 特定の送信・受信構成に依存する前に [既知の制限](docs/KNOWN_LIMITATIONS.md) を確認してください。

セットアップと各プラットフォーム固有の手順は、各アプリのディレクトリと [`docs/`](docs/) にまとめています。

## 依存関係

各プラットフォーム版は、WebRTC、AndroidX、Kotlin、NAudio、libdatachannel、Opus、OpenSSL、PortAudio、libsrtp、usrsctp、zlibなどを公式パッケージから復元します。必要な帰属表示、完全なライセンス本文、MPL-2.0のソース提供要件は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) に記載しています。ビルド設定により、この通知をAndroid、iOS、Windowsの配布物へ含めます。

## ライセンス

独自コードはプロプライエタリで、すべての権利を留保します。詳細は [LICENSE](LICENSE) を参照してください。第三者コンポーネントには個別のライセンスとソース提供義務が適用されます。
