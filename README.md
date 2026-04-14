# SSH Hub (ssh-hub)

A Scala-based SSH orchestration tool for managing and testing remote server clusters. SSH Hub allows you to define server configurations, execute scripts across multiple hosts, and monitor their execution status with an interactive terminal UI.

## Overview

SSH Hub is designed to simplify the management of distributed systems and cluster deployments. It provides:

- **Multi-server orchestration**: Define and manage multiple remote servers with proxy support
- **Script execution**: Run custom scripts on one or multiple servers simultaneously
- **Health checks**: Execute tests to verify server connectivity and health status
- **SSH tunneling**: Support for SSH jump hosts/proxies for accessing servers behind firewalls
- **Interactive UI**: Real-time monitoring of execution status and test results
- **Environment variables**: Pass custom environment variables to executed scripts

The server page looks like that:
```
┌────┬───────────────┬─────────────────┬─────┬────┐
│ Id │ Name          │ IP              │ SSH │ MC │
├────┼───────────────┼─────────────────┼─────┼────┤
│ 0  │ lille-mcl1    │ 203.0.113.100   │ ✔   │ ✔  │
│ 1  │ lille-mcl2    │ 192.168.0.27    │ ✔   │ ✔  │
│ 2  │ lille-mcl2-1  │ 192.168.0.122   │ ✔   │ ✔  │
│ 3  │ lille-mcl2-2  │ 192.168.0.86    │ ✔   │ ✔  │
│ 4  │ lille-mcl2-3  │ 192.168.0.207   │ ✔   │ ✔  │
│ 5  │ lille-mcl2-4  │ 192.168.0.130   │ ✔   │ ✔  │
│ 6  │ lille-mcl2-5  │ 192.168.0.121   │ ✔   │ ✔  │
│ 7  │ lille-mcl2-6  │ 192.168.0.231   │ ✔   │ ✔  │
│ 8  │ lille-mcl2-7  │ 192.168.0.114   │ ✔   │ ✔  │
│ 9  │ lille-mcl2-8  │ 192.168.0.76    │ ✔   │ ✔  │
│ 10 │ lille-mcl2-9  │ 192.168.0.241   │ ✔   │ ✔  │
│ 11 │ lille-mcl2-10 │ 192.168.0.112   │ ✔   │ ✔  │
│ 12 │ mcl-worker-1  │ 203.0.113.150   │ ✔   │ ✔  │
│ 13 │ mcl-worker-2  │ 203.0.113.151   │ ✔   │ ✔  │
│ 14 │ mcl-worker-3  │ 203.0.113.152   │ ✔   │ ✔  │
│ 15 │ mcl-worker-4  │ 203.0.113.153   │ ✔   │ ✔  │
│ 16 │ compute-1     │ 203.0.113.200   │ ✔   │ ✔  │
│ 17 │ compute-2     │ 203.0.113.201   │ ✔   │ ✔  │
│ 18 │ compute-3     │ 203.0.113.202   │ ✔   │ ✔  │
│ 19 │ compute-4     │ 203.0.113.203   │ ✔   │ ✔  │
│ 20 │ compute-5     │ 203.0.113.204   │ ✔   │ ✔  │
│ 21 │ compute-6     │ 203.0.113.205   │ ✔   │ ✔  │
│ 22 │ compute-7     │ 203.0.113.206   │ ✔   │ ✔  │
│ 23 │ compute-8     │ 203.0.113.207   │ ✔   │ ✔  │
│ 24 │ zeb           │ 203.0.113.208   │ ✔   │ ✔  │
└────┴───────────────┴─────────────────┴─────┴────┘
↑/↓ navigate  'r' Run Script Page  'e' Show Execution  't' Test Server  'T' SSH Terminal  's' Select Server  'S' Range Selection  Ctrl+Q quit
```

Here all the ssh connections are ok, and all the service called MC is running fine on all servers. 

## Features

### Server Management
- Define multiple servers with host addresses and login credentials
- Support for SSH proxy chains to access servers behind firewalls
- Connection status monitoring (SSH connectivity check)
- Per-server environment variables

### Script Execution
- Execute arbitrary bash scripts on remote servers
- Retry logic with configurable retry attempts
- Real-time output capture and display
- Execute multiple scripts across servers
- Test scripts for health checks and validation

### Interactive Terminal UI
- Organized pagination for large server/script lists
- Server page: View server status, SSH connectivity, and test results
- Script page: Browse available scripts
- Execution page: Monitor script execution on specific servers
- Navigation between different views


## Installation

### Prerequisites
- Java 21 or later
- Scala 3.8.3 (handled by sbt)
- SSH client
- SSH keys configured for passwordless authentication

### Build

```bash
cd ssh-hub
sbt compile
sbt run
```

To create a JAR file:
```bash
sbt assembly
```

## Configuration

SSH Hub uses YAML configuration files to define servers, scripts, and tests.

### Configuration Structure

```yaml
script:
  - name: script_name
    run: |
      # Your bash commands here
      command1
      command2

test:
  - name: test_name
    run: |
      # Your test script
      exit 0  # Exit with 0 for success

server:
  - name: server_name
    host: IP_ADDRESS
    login: USERNAME
    proxy: optional_proxy_server_name  # Optional: for SSH jump host
    env:  # Optional: environment variables
      - VAR_NAME: value
```

### Configuration Example

```yaml
script:
  - name: hostname
    run: |
      hostname
  - name: system_info
    run: |
      echo "System Information:"
      uname -a
      echo "Disk Usage:"
      df -h

test:
  - name: connectivity
    run: |
      : "${SERVICE:=$HOME/service}"
      cd $SERVICE
      SERVICE_NAME="myservice"

      CONTAINER_ID=$(sudo docker compose ps -q "$SERVICE_NAME")

      if [ -z "$CONTAINER_ID" ]; then
        echo "Service '$SERVICE_NAME' is not running"
        exit 1
      fi

      STATUS=$(sudo docker inspect -f '{{.State.Status}}' "$CONTAINER_ID")
      HEALTH=$(sudo docker inspect -f '{{.State.Health.Status}}' "$CONTAINER_ID" 2>/dev/null)

      if [ "$STATUS" != "running" ]; then
        echo "Service '$SERVICE_NAME' is not running (status: $STATUS)"
        exit 1
      fi

      if [ -n "$HEALTH" ] && [ "$HEALTH" != "healthy" ]; then
        echo "Service '$SERVICE_NAME' is unhealthy (health: $HEALTH)"
        exit 1
      fi

      echo "Service '$SERVICE_NAME' is running properly"
      exit 0

server:
  - name: gateway-01
    host: 10.0.1.100
    login: debian
  - name: cluster-node-1
    host: 10.0.2.50
    proxy: gateway-01
    login: debian
  - name: cluster-node-2
    host: 10.0.2.51
    proxy: gateway-01
    login: debian
  - name: cluster-node-3
    host: 10.0.2.52
    proxy: gateway-01
    login: debian
  - name: cluster-node-4
    host: 10.0.2.53
    proxy: gateway-01
    login: debian
```

## Usage

## Running with docker

```
docker run -it -v $PWD/config.yaml:/opt/docker/config.yaml ssh-hub:0.1.0-SNAPSHOT config.yaml
```

### Running SSH Hub

```bash
sbt run <path-to-config.yaml>
```

### Interactive Commands

Once the application is running:

- **Navigation**: Use arrow keys to navigate between servers and scripts
- **Execute script**: Select a server press 's' and select a script, then press Enter to execute
- **View results**: Select a server and press 'e' to switch to monitor the execution of a script on this server
- **Check status**: Server status indicators show:
  - SSH connectivity status (ok/failed)
  - Individual test results
  - Script execution status and output

## Architecture

### Main Components

- **Configuration.scala**: YAML configuration parsing using Circe
- **App.scala**: Core application logic including:
  - SSH command execution with jump host support
  - Server state management
  - Interactive UI rendering with Layoutz
  - Status tracking and result aggregation

### Key Features in Code

- **SSH Execution**: Uses system SSH client with support for:
  - Jump hosts/proxies via `-J` flag
  - Non-interactive mode with batch mode enabled
  - Environment variable injection
  - Automatic retry on failure
  
- **State Management**: Optics/Monocle for immutable state updates
- **UI Rendering**: Layoutz library for terminal UI layout

## Dependencies

- **Scala**: Programming language
- **Circe**: JSON/YAML parsing and serialization
- **Better Files**: File I/O utilities
- **Monocle**: Optics library for functional state updates
- **Layoutz**: Terminal UI layout library

## SSH Configuration Best Practices

### Key-based Authentication
Configure SSH keys for passwordless authentication:

```bash
# Generate SSH key pair
ssh-keygen -t ed25519 -f ~/.ssh/id_hub

# Add public key to remote servers
ssh-copy-id -i ~/.ssh/id_hub debian@10.0.2.50
```

### StrictHostKeyChecking
The tool disables `StrictHostKeyChecking` for automation purposes. For production, consider:
- Pre-populating known_hosts
- Using custom SSH config with proper security settings

## Troubleshooting

### SSH Connection Failures
- Verify SSH keys are in place
- Check network connectivity
- Ensure proxy servers are reachable
- Review SSH logs with `-v` flag

### Script Execution Errors
- Check script syntax for bash compatibility
- Verify environment variables are set correctly
- Ensure commands exist on remote systems
- Review stderr output in execution results

### Proxy/Jump Host Issues
- Verify proxy server authentication is working
- Test multi-hop connections manually
- Check for firewall/network restrictions

## License

Copyright (C) 2026 Romain Reuillon

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

## Contributing

Contributions are welcome. Please ensure:
- Code follows Scala 3 conventions
- New features are properly tested
- Documentation is updated accordingly

## Author

Romain Reuillon

## Support

For issues, questions, or contributions, please contact the project maintainer.

