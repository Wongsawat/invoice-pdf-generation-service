# ICC Color Profile for PDF/A-3

This directory should contain an sRGB ICC color profile for PDF/A-3 compliance.

## Required File

- `sRGB.icc` - sRGB IEC61966-2.1 color profile

## Download Options

### Option 1: ICC Official sRGB Profile
Download from: https://www.color.org/srgbprofiles.xalter

File: `sRGB_IEC61966-2-1_black_scaled.icc`
Rename to: `sRGB.icc`

### Option 2: Adobe sRGB Profile
Part of Adobe applications, usually found at:
- Windows: `C:\Windows\System32\spool\drivers\color\sRGB Color Space Profile.icm`
- macOS: `/System/Library/ColorSync/Profiles/sRGB Profile.icc`

## Fallback

If no ICC profile is found, the application will attempt to use PDFBox's built-in ISOcoated profile as a fallback. However, for full PDF/A-3 compliance, providing the sRGB profile is recommended.

## License

The official ICC sRGB profile is freely available for use.
