# 同梱フォント

Compose for Web は Skia でキャンバスに描画するため、ブラウザや OS のフォントを使わない。
埋め込みの既定フォントに日本語グリフが無く、同梱しないと UI の日本語がすべて豆腐（□）になる。

## NotoSansJP-Regular.ttf

- 出典: [google/fonts `ofl/notosansjp`](https://github.com/google/fonts/tree/main/ofl/notosansjp)
- ライセンス: SIL Open Font License 1.1（`OFL.txt`）

可変フォントのまま同梱すると 9.2MB になるため、weight 400 に固定したうえで
コンソールが必要とする範囲に絞ってある。再生成する場合は次のとおり。

```bash
curl -L -o NotoSansJP.ttf \
  'https://github.com/google/fonts/raw/main/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf'

uvx --from fonttools fonttools varLib.instancer NotoSansJP.ttf wght=400 \
  -o NotoSansJP-400.ttf

uvx --from fonttools pyftsubset NotoSansJP-400.ttf \
  --output-file=NotoSansJP-Regular.ttf \
  --unicodes='U+0000-00FF,U+2000-206F,U+20A0-20BF,U+2100-214F,U+2190-21FF,U+2200-22FF,U+25A0-25FF,U+3000-30FF,U+31F0-31FF,U+3200-32FF,U+4E00-9FFF,U+F900-FAFF,U+FF00-FFEF' \
  --layout-features='*' --drop-tables+=DSIG
```

WOFF2 は使えない。Skia がデコードできないため、TTF または OTF である必要がある。
