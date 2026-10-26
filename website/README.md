# HostTap product and privacy website

Static bilingual product, setup-guide, download, and privacy website for HostTap. English is served at /; Simplified Chinese is at /zh-cn/. Platform guides cover Windows, Mac, and pairing. Windows, Android, and HarmonyOS privacy policies have English and Simplified Chinese versions. No build step or dependencies are required.

Production: https://hosttap.kakahu.org/ (also available at https://hosttap-privacy.pages.dev/); English is at `/` and Simplified Chinese is at `/zh-cn/`. The public support address is kakahu@kakahu.org.

The landing pages link to the KeyTrans App Store listing and the Microsoft Store listing. The Android APK and macOS Apple silicon/Intel ZIPs are served from the public `hosttap-downloads` Cloudflare R2 bucket. Google Play remains a placeholder until its listing is available.

The Windows, Android, and HarmonyOS privacy policy pages document the practices of their respective apps and Cloudflare Pages hosting. Update the matching English and Chinese pages together when behavior changes. Shared styles are in public/styles.css. The favicon and Apple touch icon are derived from the Android HostTap app vector at `android/hosttap/src/main/res/drawable/hosttap_mark.xml`; update the site assets if that mark changes.

`public/sitemap.xml` lists the canonical public pages. `public/robots.txt` points crawlers to that sitemap. Cloudflare prepends its managed crawler directives to this static file, preserving the current managed policy.

Deploy from this directory with Wrangler 4:

    npx wrangler@4 pages deploy public --project-name hosttap-privacy --branch main

The guides are https://hosttap.kakahu.org/windows-phone-touchpad/, https://hosttap.kakahu.org/mac-phone-touchpad/, and https://hosttap.kakahu.org/pairing-setup/ (with matching `/zh-cn/` versions). The Windows privacy policy URLs are https://hosttap.kakahu.org/privacy/ and https://hosttap.kakahu.org/zh-cn/privacy/. The Android privacy policy URLs are https://hosttap.kakahu.org/android/privacy/ and https://hosttap.kakahu.org/zh-cn/android/privacy/. The HarmonyOS privacy policy URLs are https://hosttap.kakahu.org/harmonyos/privacy/ and https://hosttap.kakahu.org/zh-cn/harmonyos/privacy/. All are public and reachable without signing in.

For a dashboard upload, ZIP the contents of public/ (with index.html at the archive root), open the hosttap-privacy Pages project, choose Create deployment, upload the ZIP, and deploy to production. Include `_headers` and the complete `android/`, `harmonyos/`, `privacy/`, and `zh-cn/` paths.
