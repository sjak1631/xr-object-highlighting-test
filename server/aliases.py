"""Japanese -> English label aliases.

YOLO-World / YOLOE use a CLIP text encoder trained mostly on English, so Japanese
labels (typed or spoken on the glasses) are translated before being given to the
model. The original label is still what the client gets back. Add entries freely.
"""

JA_TO_EN: dict[str, str] = {
    "人": "person", "ひと": "person", "人間": "person",
    "コップ": "cup", "カップ": "cup", "マグカップ": "mug", "グラス": "glass",
    "ペットボトル": "plastic bottle", "ボトル": "bottle", "水筒": "water bottle",
    "椅子": "chair", "いす": "chair", "イス": "chair", "机": "desk", "テーブル": "table",
    "キーボード": "keyboard", "マウス": "computer mouse", "ノートパソコン": "laptop",
    "パソコン": "computer", "モニター": "monitor", "ディスプレイ": "monitor",
    "スマホ": "smartphone", "スマートフォン": "smartphone", "携帯": "cell phone",
    "本": "book", "ノート": "notebook", "ペン": "pen", "鉛筆": "pencil", "はさみ": "scissors",
    "鍵": "key", "かぎ": "key", "財布": "wallet", "眼鏡": "glasses", "メガネ": "glasses",
    "時計": "clock", "腕時計": "wristwatch", "傘": "umbrella", "かばん": "bag", "鞄": "bag",
    "バッグ": "bag", "リュック": "backpack", "帽子": "hat", "靴": "shoe",
    "ドア": "door", "窓": "window", "時刻表": "timetable", "看板": "sign",
    "植物": "potted plant", "観葉植物": "potted plant", "花": "flower",
    "冷蔵庫": "refrigerator", "電子レンジ": "microwave", "テレビ": "television",
    "リモコン": "remote control", "ソファ": "sofa", "ベッド": "bed", "ゴミ箱": "trash can",
    "皿": "plate", "お皿": "plate", "箸": "chopsticks", "スプーン": "spoon", "フォーク": "fork",
    "ナイフ": "knife", "りんご": "apple", "バナナ": "banana", "犬": "dog", "猫": "cat",
    "車": "car", "自転車": "bicycle", "信号": "traffic light", "消火器": "fire extinguisher",
    "コンセント": "power outlet", "ケーブル": "cable", "充電器": "charger", "ヘッドホン": "headphones",
}


def to_model_label(label: str) -> str:
    return JA_TO_EN.get(label.strip(), label.strip())
