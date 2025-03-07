# HTTP JSON Service

## How to start

### Start sandbox from a DAML Assistant project directory
```
daml-head sandbox --wall-clock-time ./.daml/dist/quickstart.dar
```

### Start HTTP service from the DAML project root
This will build the service first, can take up to 5-10 minutes when running first time.
```
$ bazel run //ledger-service/http-json:http-json-bin -- localhost 6865 7575
```
Where:
 - localhost 6865 -- sandbox host and port
 - 7575 -- HTTP service port

## Example session

```
$ cd <daml-root>/
$ daml-sdk-head

$ cd $HOME
$ daml-head new iou-quickstart-java quickstart-java
$ cd iou-quickstart-java/
$ daml-head build
$ daml-head sandbox --wall-clock-time ./.daml/dist/quickstart.dar

cd <daml-root>/
$ bazel run //ledger-service/http-json:http-json-bin -- localhost 6865 7575
```

### Choosing a party

You specify your party and other settings with JWT.  In testing
environments, you can use https://jwt.io to generate your token.

The default "header" is fine.  Under "Payload", fill in:

```
{
  "ledgerId": "??????",
  "applicationId": "foobar",
  "party": "Alice"
}
```

Replace `??????` with the ledger ID your server started with, and
`Alice` with whatever party you want to use.

Under "Verify Signature", put `secret` as the secret (_not_ base64
encoded); that is the hardcoded secret for testing.

Then the "Encoded" box should have your token; set HTTP header
`Authorization: Bearer copy-paste-token-here`.

For production use, we have a tool in development for generating proper
RSA-encrypted tokens locally, which will arrive when the service also
supports such tokens.

The below examples assume you are Alice:

### GET http://localhost:7575/contracts/search

### POST http://localhost:7575/contracts/search
application/json body:
```
{"templateIds": [{"moduleName": "Iou", "entityName": "Iou"}]}
```

### POST http://localhost:7575/command/create
application/json body:
 ```
 {
   "templateId": {
     "moduleName": "Iou",
     "entityName": "Iou"
   },
   "argument": {
     "observers": [],
     "issuer": "Alice",
     "amount": "999.99",
     "currency": "USD",
     "owner": "Alice"
   }
 }
```
output:
```
 {
     "status": 200,
     "result": {
         "agreementText": "",
         "contractId": "#20:0",
         "templateId": {
             "packageId": "bede798df37ce01fc402d266ae89d5bc4c61d70968b6a4f0baf69b24140579aa",
             "moduleName": "Iou",
             "entityName": "Iou"
         },
         "witnessParties": [
             "Alice"
         ],
         "argument": {
             "observers": [],
             "issuer": "Alice",
             "amount": "999.99",
             "currency": "USD",
             "owner": "Alice"
         }
     }
 } 
```
 
### POST http://localhost:44279/command/exercise
`"contractId": "#20:0"` is the value from the create output
application/json body:
```
{
    "templateId": {
        "moduleName": "Iou",
        "entityName": "Iou"
    },
    "contractId": "#20:0",
    "choice": "Iou_Transfer",
    "argument": {
        "newOwner": "Alice"
    }
}
```
output:
```
{
    "status": 200,
    "result": [
        {
            "agreementText": "",
            "contractId": "#160:1",
            "templateId": {
                "packageId": "bede798df37ce01fc402d266ae89d5bc4c61d70968b6a4f0baf69b24140579aa",
                "moduleName": "Iou",
                "entityName": "IouTransfer"
            },
            "witnessParties": [
                "Alice"
            ],
            "argument": {
                "iou": {
                    "observers": [],
                    "issuer": "Alice",
                    "amount": "999.99",
                    "currency": "USD",
                    "owner": "Alice"
                },
                "newOwner": "Alice"
            }
        }
    ]
}
```
