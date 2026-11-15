# Run Sift on your computer

Sift is one program (a Spring Boot server) that shows the website **and** answers searches.
Open http://localhost:8080 after starting it.

You need the data first (the index, vectors and model). It is built once with `scripts/sift-data`
and lives in the `data/` folder. If `data/index` already exists, skip that step.

## Start it
Run these from the `sift` folder.

1. Build the website (only needed the first time, or after you change files in `web/`):
   ```bash
   cd web && npm ci && npm run build && cd ..
   ```
   This turns the React code into plain files in `web/dist`.

2. Start the server:
   ```bash
   scripts/sift-api
   ```
   This builds the server program if needed (the first time takes a minute; Gradle downloads Java 21 by itself
   if you do not have it), puts the website from `web/dist` inside it, and runs it in your terminal. It keeps
   running. It is ready about 2 seconds after the build, when it prints that it started. Open http://localhost:8080 in your browser.

   (To start it in the background instead, so the terminal stays free:
   `scripts/sift-api > /tmp/sift.log 2>&1 &`, then read the log with `tail /tmp/sift.log`.)

## Stop it
- If it runs in your terminal: press `Ctrl + C`.
- If it runs in the background, or you do not know where it runs:
  ```bash
  kill $(lsof -ti tcp:8080)
  ```
  This finds the program that is using port 8080 and stops it.

## Check that it works
```bash
curl localhost:8080/health
```
Prints `{"status":"ok","documents":280748}` when the server is up.

## Handy things to try in the browser
- `how do plants make food` in **Smart** mode: Photosynthesis is near the top.
- `pyhton`: it suggests "python".
- Type `pyt` slowly: suggestions appear.
- Open the stats panel: it shows the time and whether the answer came from the cache. Search the same thing
  twice and the second time says `hit`.

## If something goes wrong
- **"Address already in use"**: something is already on port 8080. Stop it (see above) or use another port:
  `PORT=8081 scripts/sift-api`, then open http://localhost:8081.
- **The page says "No such page" (a JSON message)**: the website was not built before starting. Do step 1, then
  stop and start the server again.
- **"missing ... index"**: the data is not built. Run `scripts/sift-data` (about 25 minutes, once).

## Other ways to run it
- **Website development** (changes show at once): start the server as above, then in another terminal run
  `cd web && npm run dev` and open http://localhost:5173.
- **In Docker** (needs the data built first): `docker build -t sift . && docker run -p 8080:8080 sift`.
