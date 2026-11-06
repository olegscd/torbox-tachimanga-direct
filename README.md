# TorBox Direct for Tachimanga

Experimental proof of concept. A static GitHub Pages repo supplies the extension. The extension browses completed downloads in your own TorBox library. You add a manga magnet to TorBox yourself for this first test. There is no Nyaa catalog or magnet submission inside the extension yet.

The iPad fetches a CBZ/ZIP from TorBox into memory, extracts its pages and hands them to the reader through the extension's HTTP interceptor. This fetches the full archive before the first page and requires no manual import into Files. Start with a small CBZ of JPEG or PNG pages under 32 MiB. The extension keeps one volume in memory. There is no Python server, Tailscale connection or paid host to run.

The Android build and Tachimanga iPad runtime still need verification. Tachimanga needs to support the extension preferences, ZIP handling and intercepted page URLs. The published index uses the legacy APK layout. If your Tachimanga version requires a JAR, that packaging path will need to be implemented and tested.

## Publish on GitHub

1. Create an empty **public** repo on GitHub. Unzip this project and push all files, including the hidden `.github/workflows/publish.yml` file. From the extracted folder in PowerShell, use these commands. Replace `YOUR-USERNAME` and `YOUR-REPO`.

   ```powershell
   git init -b main
   git add .
   git commit -m "Add TorBox Direct prototype"
   git remote add origin https://github.com/YOUR-USERNAME/YOUR-REPO.git
   git push -u origin main
   ```

2. In the new repo, open **Settings → Pages** and choose **GitHub Actions** as the source.
3. Open **Actions → Build and publish test repository → Run workflow**. The workflow builds a debug test package with legacy upstream tooling and deploys the static repo. Its first green run provides the Pages base URL. Test builds can have different signing keys on each run, so updates may require reinstalling.
4. In Tachimanga, add `https://YOUR-USERNAME.github.io/YOUR-REPO/index.min.json` to **Extension Repositories** and install **TorBox Direct**. GitHub Pages hosts the installer and index only.
5. Open the **TorBox Direct** source settings and enter your TorBox API key there. Keep the key out of GitHub, workflow inputs, and chat.
6. Add a manga torrent you're allowed to download that contains a small CBZ to TorBox. Once TorBox shows it as complete, open **Browse → TorBox Direct**, pick the entry, and open the volume. A successful test means the first page loads in the Tachimanga reader.

If the workflow fails, inspect the first red step in **Actions**. If installation or reading fails, capture the exact app error with any credential or signed file link removed.
