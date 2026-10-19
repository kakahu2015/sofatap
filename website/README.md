# HostTap product and privacy website

Static bilingual product and download site for HostTap. English is served at /; Simplified Chinese is at /zh-cn/. Privacy policies are at /privacy/ and /zh-cn/privacy/. No build step or dependencies are required.

Production: https://hosttap-privacy.pages.dev/ (English) and https://hosttap-privacy.pages.dev/zh-cn/ (Simplified Chinese). The public support address is kakahu@kakahu.org.

The landing pages link to the KeyTrans App Store listing and the Microsoft Store listing. The Android APK and macOS Apple silicon/Intel ZIPs are served from the public `hosttap-downloads` Cloudflare R2 bucket. Google Play remains a placeholder until its listing is available.

Privacy policy pages document both Cloudflare Pages hosting and Cloudflare R2 downloads. Update the matching English and Chinese policy pages together when behavior changes. Shared styles are in public/styles.css.

Deploy from this directory with Wrangler 4:

    npx wrangler@4 pages deploy public --project-name hosttap-privacy --branch main

The Microsoft Store privacy policy URL is https://hosttap-privacy.pages.dev/privacy/; use /zh-cn/privacy/ for Simplified Chinese. Both are public and reachable without signing in.

For a dashboard upload, ZIP the contents of public/ (with index.html at the archive root), open the hosttap-privacy Pages project, choose Create deployment, upload the ZIP, and deploy to production. Include _headers, both privacy-policy folders, and the zh-cn/ folder.
