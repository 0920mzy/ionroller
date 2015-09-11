# Troubleshooting
## Increasing JVM heap size
For **Scala** devs: sbt >= 0.13.6
```bash
JAVA_OPTS          environment variable, if unset uses ""
-Dkey=val          pass -Dkey=val directly to the java runtime
-J-X               pass option -X directly to the java runtime (-J is stripped)
```
e.g. service configuration:
<pre><code>"run_args":["-J-Xmx200m"]</code></pre>
sbt < 0.13.6
<pre><code>"run_args":["-mem", "200"]</code></pre>

## Remove unhealthy (red) instances
```bash
ionroller drop <SERVICE_NAME> <VERSION> --force
```

## Access logs
To pull logs for your instance:

[AWS] (https://console.aws.amazon.com/) -> ElasticBeanstalk -> \<YOUR-SERVICE\> -> Logs (right side panel)

You can also find logs for your instance in S3 bucket:

elasticbeanstalk-region-\<ACCOUNT_ID\>/resources/environments/logs/publish/\<ENVIRONMENT-ID\>/\<instance-ID\>

## Log into your instance
Make sure your [SSH key is added to the instance](deployingServices.md#create-ssh-keys-to-enable-logging-in-to-the-amazon-ec2-instances).

[AWS] (https://console.aws.amazon.com/) -> EC2 -> Instances

Check public and private IP for your instance: search for instance name e.g. myservice--0-1-6-RcB. It’s part of the url: myservice--0-1-6-RcB.elasticbeanstalk.com. You can find it in ION-Roller events as environmentName: "myservice--0-1-6-RcB".


You can also search using tags e.g. ionroller:service:myservice


You’ll find IP in properties of your instance.

If your service is not in production VPC:
```bash
ssh ec2-user@<PUBLIC_IP>
```

If inside the production VPC:
Your key file must not be publicly viewable for SSH to work. Use this command if needed:
```bash
chmod 400 mykey.pem
```
Add the SSH key to your local ssh agent
```bash
ssh-add <PATH_TO_SSH_KEY>
```

Example: SSH through production gateway server, forwarding ssh agent settings:
```bash
ssh -v -A -t <PRODUCTION_GATEWAY_SERVER> ssh ec2-user@<PRIVATE_IP>
```

## Debug docker image
Connect to VM instance running the docker image.

Find java process PID
```bash
ps auxwww | grep java
```
Example:
<pre><code>
[ec2-user@ip-172-16-16-75 schema]$ ps auxwww | grep java
bin      26388 0.1 13.0 2085572 267704 ?      Ssl  12:53   0:07 /usr/lib/jvm/java-7-openjdk-amd64/bin/java -Xms1024m -Xmx1024m -XX:MaxPermSize=256m -XX:ReservedCodeCacheSize=128m -Duser.dir=/opt/docker -javaagent:/opt/docker/bin/../newrelic/newrelic.jar -cp /opt/docker/lib/myservice ...
</code></pre>
Use nsenter to connect to Docker image
```bash
sudo nsenter --target 26388 --mount --uts --ipc --net --pid
```
Service logs are available at
```bash
/opt/docker/logs
```
