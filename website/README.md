# HostTap privacy website

Static privacy policy for the Microsoft Store HostTap Windows receiver. English is served at `/`; Simplified Chinese is at `/zh-cn/`. No build step or dependencies are required.

Production: https://hosttap-privacy.pages.dev/ (English) and https://hosttap-privacy.pages.dev/zh-cn/ (Simplified Chinese). The public support address is `kakahu@kakahu.org`.

Edit `public/index.html` and `public/zh-cn/index.html` together when the policy changes. Shared styles are in `public/styles.css`.

Deploy from this directory with Wrangler 4:

```sh
npx wrangler@4 pages deploy public --project-name hosttap-privacy --branch main
```

Use the production site's root HTTPS URL as the Microsoft Store privacy policy URL. The deployment must be public and reachable without signing in.

For a dashboard upload, ZIP the contents of `public/` (with `index.html` at the archive root), open the `hosttap-privacy` Pages project, choose **Create deployment**, upload the ZIP, and deploy to production. Include `_headers` and the `zh-cn/` folder.
