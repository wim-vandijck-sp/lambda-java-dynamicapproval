# ETS : Dynamic Approvals through ETS

This provides a very simple implementation of an Event Trigger of type dynamic approval.

It will simply match the name of the requested item against a csv file in an S3 bucket.

The lambda should be triggered through the gateway API. It expects the name of the bucket to be set as a stage variable : `bucket`.

TODO :

* Make csv file configurable through stage variable.
* Make tenant-agnostic.
