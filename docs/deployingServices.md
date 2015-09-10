# Deploying services

## Before you deploy a new service...

### Create *SERVICE_NAME* role 
This role will be used as [InstanceProfile](http://docs.aws.amazon.com/IAM/latest/UserGuide/roles-usingrole-ec2instance.html) for running your service.

[AWS] (https://console.aws.amazon.com/) -> Identity and Access Management -> Roles

Role Name: \<SERVICE_NAME\>

Select Role Type: **Amazon EC2 (Allows EC2 instances to call AWS services on your behalf.)** 

Attach policy: AmazonS3ReadOnlyAccess for the minimum required permissions

*Think of all the resources that your service needs to access like DynamoDB, RDS (Postgres), etc...*

### Create S3 deployment bucket
[AWS] (https://console.aws.amazon.com/) ->  S3 -> Create bucket 

or if you have [AWS CLI](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-set-up.html) installed

`aws s3  mb s3://<DEPLOYMENT_BUCKET>`


Make sure the bucket is in the same region as Elastic Beanstalk deployments. The default region to use is US Standard (or US East 1)

### Enable pulling of images from Docker Registry
Generate the [.dockercfg](https://github.com/docker/docker/blob/master/docs/sources/userguide/dockerrepos.md#account-creation-and-login) that will be used by Elastic Beanstalk to pull images from a Docker repository. *You don’t need a .dockercfg file if the specified Docker image is in a public repository.*

Upload the .dockercfg to your deployment bucket:
[AWS] (https://console.aws.amazon.com/) ->  S3  -> \<DEPLOYMENT_BUCKET\> -> Actions -> Upload

or

`aws s3 cp ~/.dockercfg s3://<DEPLOYMENT_BUCKET>`

###  Decide on the domain for your service
[AWS] (https://console.aws.amazon.com/) -> Route53 -> Hosted Zones

The url for your service should look like 
<SERVICE_NAME>.<DOMAIN_NAME>
i.e: my-service.tools.giltaws.com

*Note the  Hosted Zone ID for your domain.*

###  Create SSH Keys to enable logging in to the Amazon EC2 instances
[AWS] (https://console.aws.amazon.com/) -> EC2 -> Key Pairs -> Create a key pair

As only one SSH key can be added for an EC2 instance, it’s recommended to create one SSH key for the team, and share it with team members.

### (Optional) Set up an external load balancer for more control over traffic migration
[AWS] (https://console.aws.amazon.com/) -> EC2 -> Load Balancers -> Create Load Balancer

You may (optionally) create a load balancer which exists independently from the Elastic Beanstalk environments, and will always point at the current release. After a successful rollout, traffic will be gradually moved from the old environment to the new environment.

The load balancer should be set up in a VPC and subnet, with access to the EC2 instances that will be set up by the service. The health check should also be configured appropriately for your service.

You will also want to consider the "Connection Draining" setting for the load balancer, as this affects how quickly traffic can be migrated (existing connections to an EC2 backend will not be terminated until this amount of time has passed).

<img src="images/ec2-lb-1.png" width="600px" />

<img src="images/ec2-lb-2.png" width="400px" />

## Let's (ION-)roll! On to the actual deployment

###  Add your service configuration to ION-Roller
Prepare a JSON file with the configuration for your service, e.g. my-service-config.json.

If there are any services configured already, you can start with the getting the config of one of them, e.g.:

```bash
ionroller config my-service > my-service-config.json
```

Configuration template:

```json
{
   "url":"<URL>", 
   "hosted_zone_id":"<HOSTED_ZONE_ID>",
   "aws_account_id":"<AWS_ACCOUNT_ID>",
   "service_role":"<SERVICE_ROLE>",
   "image":"<DOCKER_REPOSITORY>/<IMAGE>",
   "port_mappings":[
      {
         "internal":9000,
         "external":9000
      }
   ],
   "volume_mappings":[],
   "run_args":["-DtestProperty=HelloWorld"],
   "eb":{
      "deployment_bucket":"<DEPLOYMENT_BUCKET>",
      "stack":"64bit Amazon Linux 2015.03 v1.4.3 running Docker 1.6.2",
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
            "Value":"t2.medium"
         },
         {
            "Namespace":"aws:autoscaling:asg",
            "OptionName":"MinSize",
            "Value":"2"
         },
         {
            "Namespace":"aws:autoscaling:asg",
            "OptionName":"MaxSize",
            "Value":"4"
         },
         {
            "Namespace":"aws:autoscaling:launchconfiguration",
            "OptionName":"EC2KeyName",
            "Value":"<KEYNAME>"
          }
      ],
      "resources": {
            "<RESOURCE_ID>":{
              "Type":"<CLOUDFORMATION_TYPE>",
              "Properties":{
                "<PROPERTY_NAME1>":"<PROPERTY_VALUE1>",
                "<PROPERTY_NAME2>":"<PROPERTY_VALUE2>"
              },
            },     
          },
      },
      "remove_unused_after_minutes":1
   }
```

> (Optional) If you have configured an ELB which exists separately from each environment, you should add an extra key at the top level of the JSON configuration:
> ```
>    "external_elb": {
>      "name": "<ELB_NAME>",
>      "security_group": "<SECURITY_GROUP_OF_ELB_INSTANCES>",
>      "rollout_delay_minutes": <MINUTES_BETWEEN_TRAFFIC_INCREMENTS>
>    }
> ```
> 
> You can find a list of the security groups used by the ELB in the EC2 console:
> 
> [AWS] (https://console.aws.amazon.com/) -> EC2 -> Load Balancers -> Select load balancer -> Security
>
> The security group setting is used to allow the load balancer access to communicate with running services, and will have a name beginning with "sg-".

Set your new service configuration via:

```bash
ionroller set_config my-service /Users/abc/my-service-config.json
```

###  Release a new version
Assuming you have pushed Docker image with your service to Docker registry tagged with \<VERSION\>:

```bash
ionroller release <SERVICE_NAME> <VERSION>
```

###  Drop version
You can cancel scheduled deployment or even remove existing instance of service via

```bash
ionroller drop <SERVICE_NAME> <VERSION> [--force]
```
`--force` is required if you want to drop the version that is currently serving traffic.

###  ION-Roller UI
You can check the list of services configured for ION-Roller deployments and track all deployment events in UI at
\<YOUR_IONROLLER_SERVICE_URL\>

![UI home](images/ui-1.png)

![UI services](images/ui-2.png)

![UI events](images/ui-3.png)
