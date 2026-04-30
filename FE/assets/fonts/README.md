# Fonts

Yeosal uses **Wanted Sans** as its primary KR family. Wanted Sans is open-source under the SIL OFL 1.1 license.

## Where to get the files

Download the OTF binaries from the official release page:

- https://github.com/wanteddev/wanted-sans/releases

Pick the latest release zip and copy these four weights into this directory:

```
FE/assets/fonts/
├── WantedSans-Regular.otf      (weight 400)
├── WantedSans-Medium.otf       (weight 500)
├── WantedSans-Bold.otf         (weight 700)
└── WantedSans-ExtraBold.otf    (weight 800)
```

File names must match exactly — `src/lib/fonts.ts` references them by literal path.

## Activating

After the .otf files are in place, edit `FE/src/lib/fonts.ts` and uncomment the four `require(...)` lines inside `FONT_MAP`. Then restart Metro:

```bash
cd FE
npm run start -- --reset-cache
```

## What's already wired

- `FE/src/theme/typography.ts` already declares `fontFamily: "WantedSans"`. Until the binaries land, RN falls back to the system font.
- `FE/src/lib/fonts.ts` exports `useWantedSans()`; `FE/app/_layout.tsx` calls it and gates the splash so text renders with the correct font on first paint.
