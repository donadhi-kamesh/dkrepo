# dkrepo - Cloudstream Extension Repository

This is a custom extension repository for [Cloudstream 3](https://github.com/recloudstream/cloudstream), maintained by **dkrepo**.

![Build Status](https://github.com/donadhi-kamesh/dkrepo/actions/workflows/build_plugins.yml/badge.svg)

---

## 🚀 Extensions Included

### 1. **Re:ANIME** (`ReAnime`)
- **Site**: [https://reanime.to](https://reanime.to)
- **Content**: Anime Series, Movies & OVAs
- **Language**: English (Sub & Dub)
- **Features**:
  - Home sections (Top Anime, Popular Anime, Latest Releases)
  - Fast search integration via Re:ANIME REST API v1
  - Full details page with metadata, cover/banner images, genres, status, and plot
  - Automatic episode links and stream link extractors

---

### 2. **MoviesWood** (`MoviesWood`)
- **Site**: [https://movieswood.cloud](https://movieswood.cloud)
- **Content**: Telugu / Tamil / Hindi / Malayalam / Dubbed movies, Web Series & TV Shows (direct download links)
- **Language**: Multi (Indian languages + English dubs)
- **Features**:
  - Home sections per category (Tamil, Telugu Dubbed, English, Hindi, Malayalam, Web Series, TV Shows)
  - Site-wide search across all categories (search is per-category on the site, fanned out in parallel)
  - TMDB posters, year, rating & synopsis on the info page
  - Every file on the site (1080p / 720p / 700MB …) exposed as a playable/downloadable link
  - Season/episode detection for web series (S03 EP02 → episode list)
- **Note**: the site only serves content to mobile user agents; the extension always sends a mobile UA.

---

## 📱 How to Add to Cloudstream 3

1. Open **Cloudstream 3** on your Android device.
2. Go to **Settings** ⚙️ > **Extensions**.
3. Tap **Add Repository**.
4. Enter the Repository URL:
   ```text
   https://raw.githubusercontent.com/donadhi-kamesh/dkrepo/gh-pages/repo.json
   ```
5. Click **Add** and install **Re:ANIME** or **MoviesWood** from the extensions list!

If Cloudstream still shows an old version (e.g. v12) or download fails:
1. Remove the **dkrepo** repository from Extensions.
2. Force-stop Cloudstream (or clear the app cache).
3. Re-add the repository URL above (use the `raw.githubusercontent.com` URL, not jsDelivr).
4. Install/update **ReAnime** again — current release is on the `gh-pages` branch.

---

## 🛠️ Building Locally

To build the plugin locally using Gradle:
```bash
./gradlew ReAnime:make
```
The output plugin `.cs3` / `.jar` file will be generated in `build/`.
