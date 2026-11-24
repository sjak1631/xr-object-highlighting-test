# XR Object Highlighter (XREAL Aura / Android XR)

XREAL Aura の前方カメラ映像から、**指定した任意のラベル**に該当する物体をすべて検出し、ワールド固定の枠でリアルタイムにハイライトします。

- ラベルは自由記述（`cup, red backpack, person wearing glasses` など）。パネルの入力欄か音声入力で指定
- 検出はオープンボキャブラリ型モデル（YOLO-World / YOLOE）を使うため、固定クラスに縛られない
- よく使う日本語ラベル（コップ、椅子、スマホなど）はサーバー側で英語に変換（[server/aliases.py](server/aliases.py)）

## 構成

```
┌──────── XREAL Aura（Android XR アプリ）──────┐          ┌──── PC（GPU 推奨）──────────┐
│ CameraX → JPEG 640px ＋ 撮影時の頭部姿勢     │ ─ WS ──▶ │ server.py                    │
│                                             │          │  YOLO-World / YOLOE          │
│ HighlightController                         │ ◀─ WS ── │  set_classes(labels)         │
│  撮影時の姿勢でボックス中心からレイを作成   │  boxes   └──────────────────────────────┘
│  → ARCore hitTest（平面・深度）で距離を推定 │
│  → ラベル＋角度で追跡・平滑化               │
│  → PanelEntity（発光枠）をワールド固定配置  │
└─────────────────────────────────────────────┘
```

設計のポイント:

- **遅延補償**: 各フレームに撮影時の頭部姿勢を付け、その姿勢で 3D 位置を復元します。推論中に頭が動いても、ハイライトは物体の実際の位置に出ます。
- **Stop-and-wait 送信**: 前のフレームの結果が届いてから次を送るので、ネットワークが遅くても遅延が積み上がりません。
- **ワールド固定表示**: ハイライトは SceneCore の `PanelEntity` として 3D 空間に置き、毎フレームユーザーの方へ向けます。視野から外れた物体も最大 10 秒は残るため、見回して戻ってもハイライトが付いたままです。
- **光学シースルー向け描画**: Aura のディスプレイは加算合成（黒が透明）なので、明るい線と薄い色だけで描きます。

## ディレクトリ

| パス | 内容 |
|---|---|
| [server/server.py](server/server.py) | WebSocket 推論サーバー（FastAPI） |
| [server/detector.py](server/detector.py) | YOLO-World / YOLOE のラッパー（実行中にラベル変更可） |
| [server/webcam_demo.py](server/webcam_demo.py) | グラスなしで PC の Web カメラで試すクライアント |
| [android/](android/) | Android XR アプリ（Kotlin、Jetpack XR SceneCore / ARCore、CameraX、Compose） |
| […/HighlightController.kt](android/app/src/main/java/com/example/xrhighlight/HighlightController.kt) | 2D → 3D 変換、追跡、パネル配置 |
| […/CameraFrameSource.kt](android/app/src/main/java/com/example/xrhighlight/CameraFrameSource.kt) | カメラ取得、FOV 推定、フレーム送信 |

## 1. 推論サーバー（PC）

```bash
cd server
python -m venv .venv
# Windows: .venv\Scripts\activate ／ macOS・Linux: source .venv/bin/activate
# NVIDIA GPU がある場合は、先に CUDA 版 torch を入れる（pytorch.org 参照）
pip install -r requirements.txt

python server.py --model yolov8m-worldv2.pt --port 8765
```

- モデルは `yolov8{s,m,l,x}-worldv2.pt`（YOLO-World）か `yoloe-11{s,m,l}-seg.pt`（YOLOE）。初回起動時に自動でダウンロードされます（CLIP テキストエンコーダも約 340 MB 追加）。
- 参考値: このマシンの CPU で `yolov8s-worldv2` を使うと 1 フレーム約 80〜150 ms。リアルタイムで使うなら GPU 推奨です。
- ラベルの切り替えは初回約 2 秒かかり、以降はキャッシュされます。

グラスなしでの動作確認:

```bash
python webcam_demo.py --server ws://localhost:8765/ws --labels "cup, bottle, person"
# ウィンドウで l キー → ターミナルで新しいラベルを入力、q で終了
```

## 2. Android XR アプリ（XREAL Aura）

必要なもの: Android Studio（または JDK 17 ＋ Android SDK API 37）、adb で接続した XREAL Aura。

```bash
cd android
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

使い方:

1. 起動時にカメラ・頭部トラッキング・シーン理解の権限を許可します。
2. 「推論サーバー」に PC の IP を入力します（例: `ws://192.168.0.10:8765/ws`）。PC と Aura は同じネットワークに置き、ファイアウォールでポート 8765 を許可してください。
3. ラベル（例: `コップ, keyboard, スマホ`）を入力するか「🎤 音声で指定」を使い、「開始」を押します。
4. 実行中にラベルを変えるときは「ラベル適用」を押します。

普通のスマホでも動きますが、XR がない環境ではハイライトは描かれず、検出結果の一覧だけが出ます（接続確認用）。

## 3. キャリブレーション

Android XR の API からは、頭部姿勢に対する RGB カメラの正確な位置・角度が直接取れません。アプリは「カメラ＝頭部姿勢」と仮定しているため、少しずれることがあります。
机の上の物体を 1 つ検出させ、「キャリブレーション」のスライダーで枠が実物に重なるよう調整してください。

- **Yaw / Pitch / Roll**: カメラ光軸の角度ずれ（枠が常に左右・上下にずれるとき）
- **FOV スケール**: 視野角推定の補正（視野の端ほどずれが大きいとき）
- **カメラ位置 X / Y**: カメラ位置のオフセット（近くの物体で効く）
- **既定距離**: ヒットテストで面が見つからないときに使う距離（宙に浮いた物体など）

設定は端末に保存されます。

## 通信プロトコル

```
client → server  text   {"type":"config","labels":["cup","bottle"],"conf":0.25}
client → server  binary [int64 big-endian frame_id][JPEG]
server → client  text   {"type":"detections","frame_id":1,"infer_ms":18.2,"width":640,"height":480,
                         "detections":[{"label":"cup","score":0.81,"box":[x1,y1,x2,y2]}]}  // box は 0..1
```

## 既知の制約と今後

- **実機では未検証です。** 確認済み: `./gradlew assembleDebug` のビルド成功、アプリと同じプロトコルを使ったテスト画像でのサーバー通し動作（日本語ラベルを含む）。Aura 実機での確認が必要なもの: CameraX から前方カメラを取得できるか、ヒットテストとパネル配置が期待どおり動くか。
- Jetpack XR はアルファ版です（`runtime 1.1.0-alpha03`、`scenecore/arcore 1.1.0-alpha02`）。API が変わる可能性があります。
- 距離は平面・深度へのヒットテストで決めています。壁の手前に浮いた小物は壁の深さに置かれることがあります。`Depth.mono(session)` でボックス中心の深度を直接取ると改善できます。
- ハイライトは矩形です。YOLOE の `-seg` モデルならマスクも得られるので、輪郭表示にもできます。
- サーバーなしで動かしたい場合は、YOLO-World を固定クラスで TFLite に書き出してオンデバイス推論する方法があります（ラベルを変えるたびに書き出し直しが必要）。
