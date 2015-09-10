# Setting up ION-Roller service and ION-Roller CLI

Install ION-Roller CLI

```bash
curl -s https://s3.amazonaws.com/ionroller-cli/install | sh
```

Test that ION-Roller CLI works properly:

```bash
ionroller 
```
should display the list of available commands, i.e.

```bash
ionroller 
v0.0.124

Usage: ionroller COMMAND [--base BASE_URL]

Available options:
  -h,--help                Show this help text
  COMMAND                  Command to run
  --base BASE_URL          Base URL for ionroller service

Available commands:
  release                  Release version: ionroller release SERVICE VERSION [-c|--conf
                           ISO_8601_DATE]
  drop                     Drop version: ionroller drop SERVICE VERSION [-c|--conf
                           ISO_8601_DATE] [--force]
  events                   Show events for service: ionroller events SERVICE [VERSION]
                           [-f|--from ISO_8601_DATE] [-t|--to ISO_8601_DATE]
  config                   Get configuration: ionroller config SERVICE [-t,--timestamp
                           ISO_8601_DATE]
  set_config               Set configuration: ionroller set_config SERVICE [FILE]
  delete_config            Delete configuration: ionroller delete_config SERVICE
  configs                  Get configuration history timestamps: ionroller configs
                           SERVICE [-f|--from ISO_8601_DATE] [-t|--to ISO_8601_DATE]
  current                  Get current version: ionroller current SERVICE
  update                   Update ionroller CLI
  setup                    Setup ionroller service
  version                  ionroller version
  set_base_url             Set ionroller base URL
  set_client_update_url    Set ionroller CLI update URL
```

Let ION-Roller create IAM role and DynamoDB tables required to run the service:

```bash
ionroller setup
```

Deploy ION-Roller with ION-Roller CLI (so meta... ;-) ). 

First add configuration of your ION-Roller service to DynamoDB.

Fill all required values in the configuration template below and save it as ionroller-config.json.

**Configuration template for ION-Roller service**:

```json
{
   "url":"ionroller.<YOUR_DOMAIN>", 
   "hosted_zone_id":"<HOSTED_ZONE_ID>",
   "aws_account_id":"<AWS_ACCOUNT_ID>",
   "service_role":"ionroller",
   "image":"giltouroboros/ionroller",
   "port_mappings":[
      {
         "internal":9000,
         "external":9000
      }
   ],
   "volume_mappings":[],
   "run_args":[
        "-Dpidfile.path=/dev/null",
        "-Dionroller.modify-environments-whitelist=ALL",
        "-Dionroller.modify-environments-blacklist=ionroller"],
   "eb":{
      "deployment_bucket":"<DEPLOYMENT_BUCKET>",
      "settings":[
         {
            "Namespace":"aws:ec2:vpc",
            "OptionName":"VPCId",
            "Value":"<VPC_ID>"
         },
         {
            "Namespace":"aws:ec2:vpc",
            "OptionName":"Subnets",
            "Value":"<SUBNET_ID>"
         },
         {
            "Namespace":"aws:ec2:vpc",
            "OptionName":"ELBSubnets",
            "Value":"<SUBNET_ID>"
         },
         {
            "Namespace":"aws:autoscaling:launchconfiguration",
            "OptionName":"SecurityGroups",
            "Value":"<SECURITY_GROUP_ID>"
         },
         {
            "Namespace":"aws:ec2:vpc",
            "OptionName":"AssociatePublicIpAddress",
            "Value":"true"
         },
         {
            "Namespace":"aws:ec2:vpc",
            "OptionName":"ELBScheme",
            "Value":"internal"
         },
         {
            "Namespace":"aws:elb:loadbalancer",
            "OptionName":"CrossZone",
            "Value":"true"
         },
         {
            "Namespace":"aws:autoscaling:launchconfiguration",
            "OptionName":"InstanceType",
            "Value":"t2.micro"
         },
         {
            "Namespace":"aws:autoscaling:asg",
            "OptionName":"MinSize",
            "Value":"1"
         },
         {
            "Namespace":"aws:autoscaling:asg",
            "OptionName":"MaxSize",
            "Value":"1"
         },
         {
            "Namespace":"aws:autoscaling:launchconfiguration",
            "OptionName":"EC2KeyName",
            "Value":"<KEYNAME>"
          }
      ],
      "remove_unused_after_minutes":0
   }
```


```bash
ionroller set_config ionroller ionroller-config.json
```

Then trigger actual deployment:

```bash
ionroller release ionroller <IONROLLER_VERSION>
```
\<IONROLLER_VERSION\> matches the latest [ION-Roller Docker Image tag] (https://hub.docker.com/r/giltouroboros/ionroller/tags/).

Point ION-Roller CLI to ION-Roller service:

```bash
ionroller set_base_url <YOUR_IONROLLER_SERVICE_URL>
```

Test that ION-Roller CLI works properly:

```bash
ionroller current ionroller
```

should return current version of the service, i.e.

```bash
current: 0.0.124
```

ION-Roller web UI should be available at **\<YOUR_IONROLLER_SERVICE_URL\>**:


![UI home](images/ui-1.png)

![UI services](images/ui-2.png)

![UI events](images/ui-3.png)

# Cross-account deployments

ION-Roller can deploy services to another AWS accounts. 

To enable this you have to create **ionroller** role in the other AWS account. It will be used for cross account access and managing resources.

[AWS] (https://console.aws.amazon.com/) -> Identity and Access Management -> Roles

Role Name: **ionroller**

Select Role Type: **Amazon EC2 (Allows EC2 instances to call AWS services on your behalf.)**

Attach policy: AWSElasticBeanstalkFullAccess and AmazonRoute53FullAccess

Edit Trust Relationship for ionroller role: paste the policy:
```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Sid": "1",
         "Effect": "Allow",
         "Principal": {
           "AWS": "arn:aws:iam::<IONROLLER_ACCOUNT_ID>:root"
         },
         "Action": "sts:AssumeRole"
       },
       {
         "Sid": "2",
         "Effect": "Allow",
         "Principal": {
           "Service": "ec2.amazonaws.com"
         },
         "Action": "sts:AssumeRole"
       }
     ]
   }
```

*IONROLLER_ACCOUNT_ID:* the AWS account id where ION-Roller service runs

Mind that `"aws_account_id":"<AWS_ACCOUNT_ID>"` in your service config should now point the new AWS account. 

Now you can proceed with [deploying services](deployingServices.md).
