# Development 
## Running Server

```bash
sbt -Dionroller.modify-environments-whitelist=[ALL|<TIMELINE_NAME_1,TIMELINE_NAME_2,...>] "project www" run
```

Note there's also 
```bash
-Dionroller.modify-environments-blacklist
```
available

#####  Testing CLI with local server

```bash
sbt "project ionroller_cli" "run --base http://localhost:9000 <COMMAND> <SERVICE_NAME>"
```

#####  Releasing new version of CLI 

Create a S3 bucket called 'ionroller-cli'. Then:

```bash
sbt releaseCli
```

