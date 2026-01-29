# Thai Fonts for Invoice PDF Generation

This directory should contain Thai fonts for PDF/A-3 generation.

## Required Fonts

### Option 1: TH Sarabun New (Recommended)
Official Thai government font, free to use.

Download from: https://www.f0nt.com/release/th-sarabun-new/

Required files:
- `THSarabunNew.ttf` (Regular)
- `THSarabunNew-Bold.ttf` (Bold)
- `THSarabunNew-Italic.ttf` (Italic)
- `THSarabunNew-BoldItalic.ttf` (Bold Italic)

### Option 2: Noto Sans Thai (Alternative)
Google's open source font with excellent Thai support.

Download from: https://fonts.google.com/noto/specimen/Noto+Sans+Thai

Required files:
- `NotoSansThai-Regular.ttf`
- `NotoSansThai-Bold.ttf`

## Installation

1. Download the font files from one of the sources above
2. Copy the `.ttf` files to this directory
3. Rebuild the application

## Docker Usage

When using Docker, fonts can be mounted as a volume:

```bash
docker run -v /path/to/fonts:/app/fonts invoice-pdf-generation-service
```

Or install system fonts in the Dockerfile:

```dockerfile
RUN apk add --no-cache font-noto font-noto-thai
```

## License

- TH Sarabun New: Public Domain (Thai Government)
- Noto Sans Thai: SIL Open Font License
