## Build
This project builds with [`sbt`](http://www.scala-sbt.org/download.html).

## Run
There are two subprojects: `client` and `node`. The `client` is used to submit transactions to the system,
and the `node` processes, validates, and (optionally) mines blocks.

To run either project use `sbt [project name]/run [arguments]`. The arguments and their functions
are listed if the `-h` flag is passed, but a sample configuration is described
below:

To run a swarm of local nodes (starting at port 9147 and increasing):
```sbtshell
> node/run --node_count [nodeCount] [--mine]
```

To run a node exposed on a particular IP (nodes are only visible to loopback by default):

```sbtshell
> node/run --bind [IP address] --port [port] [--mine]
```

To run the client (submits a transaction for 1200 to wallet with pubkey `0xdeadbeef`):

```sbtshell
> client/run --port [targetPort] -a [ip address]
```


Nodes will start HTTP servers at their node port + 1000 which display the current state of their blockchain and ledger.
