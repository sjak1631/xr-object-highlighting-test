# XR Object Highlighter (XREAL Aura / Android XR)

XREAL Aura の前方カメラ映像から、**指定した任意のラベル**に該当する物体をすべて検出し、空間に固定したピン（物体の上の印＋線＋ラベル）でリアルタイムに示します。

- ラベルは自由記述（`cup, red backpack` など）。パネルの入力欄か音声入力で指定
- 検出はオープンボキャブラリ型モデル（YOLO-World / YOLOE）を使うため、固定クラスに縛られない
- よく使う日本語ラベル（コップ、椅子、スマホなど）はサーバー側で英語に変換（[server/aliases.py](server/aliases.py)）

## 仕組み

```
Aura ─ camera ──frame + 撮影時の頭部姿勢──▶ PC: server.py（YOLO-World 検出 ＋ 自動キャリブレーション）
                                                  │ boxes / calibration
  ┌───────────────────────────────────────────────┘
  ▼
locate   箱 → 空間の視線（レイ）＋距離の手がかり        RayObjectLocator
tracking 複数回の観測 → 安定した物体（固定・移動・削除）  ObjectTracker
render   物体ごとのピンを毎フレーム描画                  PinRenderer
```

1. **位置推定（locate）**: 撮影した瞬間の頭部姿勢（姿勢履歴から補間）とカメラの補正値から、箱の中心を通る視線を作ります。距離は ARCore のヒットテストで推定します（箱の下端の視線が机・床に当たる点を優先）。
2. **追跡（tracking）**
   - 3 回続けて見えた物体を、三角測量した位置でアンカーに固定します。その後の検出は確認と位置の微修正だけに使うので、揺れません。
   - 固定位置から明らかにずれて見えたら「移動中」として追いかけ、止まったら固定し直します。
   - 見えるはずなのに検出されない物体は消し、同じラベルの重複はまとめます。
3. **表示（render）**: ピンは常にユーザーの方を向き、距離によらず同じ大きさに見えます。動きは補間し、出現・消滅はフェードします。Aura は加算合成のディスプレイ（黒が透明）なので、明るい線だけで描いています。

### 自動キャリブレーション

Aura の Camera2 メタデータは仮の値で、実際より視野角が約 14° 広く申告されます。そこで [server/calibration.py](server/calibration.py) が、頭を回したときの「頭部トラッキングの回転」と「画像上の特徴点の動き」を突き合わせ、次の値を推定します。

- 視野角、光学中心、レンズ歪み
- 頭に対するカメラの取り付け角度
- 映像と姿勢の時刻ずれ

推定値は端末に保存され、次回以降も使われます。やり直すときはパネルの「やり直す」を押してください。

## ディレクトリ

| パス | 内容 |
|---|---|
| [server/server.py](server/server.py) | WebSocket 推論サーバー（FastAPI） |
| [server/detector.py](server/detector.py) | YOLO-World / YOLOE のラッパー（実行中にラベル変更可） |
| [server/calibration.py](server/calibration.py) | カメラの自動キャリブレーション |
| [server/webcam_demo.py](server/webcam_demo.py) | グラスなしで PC の Web カメラで試すクライアント |
| `android/…/xrhighlight/` | Android XR アプリ（Kotlin、Jetpack XR、CameraX、Compose） |
| 　[MainActivity.kt](android/app/src/main/java/com/example/xrhighlight/MainActivity.kt) | 全体の配線（UI・カメラ・通信・位置推定・追跡・描画） |
| 　[xr/](android/app/src/main/java/com/example/xrhighlight/xr/) | XR セッション（Full Space・トラッキング設定）、頭部姿勢の履歴 |
| 　[camera/](android/app/src/main/java/com/example/xrhighlight/camera/) | カメラ取得、撮影時の姿勢を付けてフレーム送信 |
| 　[net/](android/app/src/main/java/com/example/xrhighlight/net/) | サーバーとの WebSocket 通信 |
| 　[geometry/](android/app/src/main/java/com/example/xrhighlight/geometry/) | カメラモデル、キャリブレーション結果、ベクトル計算 |
| 　[locate/](android/app/src/main/java/com/example/xrhighlight/locate/) | 箱 → 空間（`ObjectLocator` インターフェースと実装、空間アノテーションの検証コード） |
| 　[tracking/](android/app/src/main/java/com/example/xrhighlight/tracking/) | 物体の追跡（固定・移動・存在判定・重複統合・三角測量） |
| 　[render/](android/app/src/main/java/com/example/xrhighlight/render/) | ピンの描画 |
| 　[ui/](android/app/src/main/java/com/example/xrhighlight/ui/) | 操作パネル |

## 1. 推論サーバー（PC）

```bash
cd server
python -m venv .venv
# Windows: .venv\Scripts\activate ／ macOS・Linux: source .venv/bin/activate
# NVIDIA GPU がある場合は、先に CUDA 版 torch を入れる（pytorch.org 参照）
pip install -r requirements.txt

python server.py --model yolov8s-worldv2.pt --port 8765 --host 127.0.0.1
```

- モデルは `yolov8{s,m,l,x}-worldv2.pt`（YOLO-World）か `yoloe-11{s,m,l}-seg.pt`（YOLOE）。初回起動時に自動でダウンロードされます（CLIP テキストエンコーダも約 340 MB 追加）。
- 参考値: このマシンの CPU で `yolov8s-worldv2` を使うと 1 フレーム約 80〜140 ms。GPU を使えばもっと速くなります。

グラスなしでの動作確認:

```bash
python webcam_demo.py --server ws://localhost:8765/ws --labels "cup, bottle, person"
```

## 2. Android XR アプリ（XREAL Aura）

必要なもの: JDK 17 ＋ Android SDK（API 37）、adb で接続した XREAL Aura（ワイヤレスデバッグ可）。

```bash
cd android
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8765 tcp:8765      # Aura の 127.0.0.1:8765 → PC のサーバー（ファイアウォール設定不要）
```

使い方:

1. 「推論サーバー」が `ws://127.0.0.1:8765/ws` になっていることを確認します（`adb reverse` を使う場合）。
2. ラベル（例: `コップ, keyboard`）を入力するか「🎤 音声で指定」を使い、「開始」を押します。
3. 初回は頭をゆっくり上下左右に動かして周囲を見回すと、自動キャリブレーションが完了します（パネルに「✓ 完了」と表示）。
4. 実行中にラベルを変えるときは「ラベル適用」を押します。

普通のスマホでも動きますが、XR がない環境ではピンは描かれず、検出結果の一覧だけが出ます（接続確認用）。

## 通信プロトコル

```
client → server  text   {"type":"config","labels":["cup","bottle"],"conf":0.25}
client → server  text   {"type":"calib_reset"}
client → server  binary [int64 frame_id][uint32 header_len][JSON header][JPEG]
                        header = {"ts": ns, "q": [x,y,z,w], "p": [x,y,z], "w": [wx,wy,wz]}  // 撮影時の頭部姿勢と角速度
server → client  text   {"type":"detections","frame_id":1,"infer_ms":18.2,"width":640,"height":480,
                         "detections":[{"label":"cup","score":0.81,"box":[x1,y1,x2,y2]}]}  // box は 0..1
server → client  text   {"type":"calibration","q":[...],"tan_h":..,"tan_v":..,"cx":..,"cy":..,"k1":..,"k2":..,
                         "dt_ms":..,"rms_px":..,"pairs":..,"quality":..,"converged":true, ...}
```

## 既知の制約と今後

- Jetpack XR はアルファ版です（`runtime 1.1.0-alpha03`、`scenecore/arcore 1.1.0-alpha02`）。
- 起動モードは `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED` にしてください。`UNMANAGED` では SceneCore のパネルが表示されません。
- **空間アノテーション（システム側での 3D 追跡）**: Aura のシステムには追跡サービスが入っていますが、ビルド `UXP4.261012.001.O5` ではアプリ向けの OpenXR 拡張 `ANDROID_spatial_annotation_tracking` が公開されていないため使えません。使えるようになれば、`ObjectLocator` を差し替えるだけで、自前のキャリブレーションと距離推定が不要になります。
  - 開発者側の条件（Spatial API レベル 3 以上：実機は 4、`SCENE_UNDERSTANDING_FINE` 権限、`arcore` 1.1.0-alpha01 以上と `@OptIn`）はすべて満たしています。
  - 使えない原因は、ランタイムがこの拡張を一覧に出していないことです。ランタイムを解析すると、内部の展開用フラグ `com.android.xr.flags.enable_oxr_android_spatial_extensions`（名前空間 `com_android_xr`）がこのビルドではオフになっていました。製品版ビルドではこのフラグを変更できないため、システム更新（OTA）で有効になるのを待つ必要があります。
- 距離推定の改善案: システムの深度マップ（`Depth` ＋ `RenderViewpoint`）を使う。
- 対象物が決まっている実験では、小型の検出モデルをファインチューンし、端末上で推論するのが速くて正確です。
