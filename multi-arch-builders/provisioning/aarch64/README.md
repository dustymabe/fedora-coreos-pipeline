# OpenTofu

 OpenTofu is a Terraform fork, is an open-source infrastructure as code (IaC) tool
 lets you define both cloud and on-prem resources in human-readable configuration files
 that you can version, reuse, and share.

 To proceed with the next steps, ensure that 'tofu' is installed on your system.
 See: https://github.com/opentofu/opentofu/releases

## Before starting

### AWS credentials

```bash
# Add your credentials to the environment.
# Be aware for aarch64 the region is us-east-2
HISTCONTROL='ignoreboth'
 export AWS_DEFAULT_REGION=us-east-2
 export AWS_ACCESS_KEY_ID=XXXX
 export AWS_SECRET_ACCESS_KEY=YYYYYYYY
```

Make sure your AMI user has access to this policies:

```json
{
	"Version": "2012-10-17",
	"Statement": [
		{
			"Effect": "Allow",
			"Action": "ec2:*",
			"Resource": "*"
		}
	]
}
```

## Running tofu
```bash
   # To begin using it, run 'init' within this directory.
   tofu init
   # If you don't intend to make any changes to the code, simply run it:
   tofu apply
   # If you plan to make changes to the code as modules/plugins, go ahead and run it:
   tofu init -upgrade
   # To destroy it run:
   tofu destroy -target aws_instance.coreos-multiarch-builder-aarch64 
```
## Issues in tofu

To auto-generate our ignition file, we initially create an empty 'coreos-aarch64-builder.ign'
file to satisfy Tofu's pre-checks.
The subsequent issue arises when this file is modified. As a workaround, run 'tofu apply' twice.

See: https://github.com/hashicorp/terraform-provider-aws/issues/19583

This still an issue in aws 5.20.1


```
 Error: Provider produced inconsistent final plan
│
│ When expanding the plan for aws_instance.coreos-multiarch-builder-aarch64 to include new values learned so far during apply,
│ provider "registry.opentofu.org/hashicorp/aws" produced an invalid new value for .user_data: was cty.StringVal(""), but now
│ cty.StringVal("dd69e0740ec90a21a0474673c8623c7faa964f07").
│
│ This is a bug in the provider, which should be reported in the provider's own issue tracker.
```

