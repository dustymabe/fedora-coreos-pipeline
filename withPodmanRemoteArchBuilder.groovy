// Run in a podman remote context on a builder of the given
// architecture.
//
// Available parameters:
//    arch:  string -- The architecture of the desired host
def withPodmanRemoteArchBuilder(params = [:], Closure body) {
    arch = params['arch']
    withPodmanRemote(remoteHost: "fcos-${arch}-builder-host-string",
                     remoteUid:  "fcos-${arch}-builder-uid-string",
                     sshKey:     "fcos-${arch}-builder-sshkey-key") {
        body()
    }
}

// Run in a cosa remote session context on a builder of the given
// architecture.
//
// Available parameters:
// session:  string -- The session ID of the already created session
//    arch:  string -- The architecture of the desired host
def withExistingCOSARemoteSession(params = [:], Closure body) {
    arch = params['arch']
    session = params['session']
    withPodmanRemote(remoteHost: "fcos-${arch}-builder-host-string",
                     remoteUid:  "fcos-${arch}-builder-uid-string",
                     sshKey:     "fcos-${arch}-builder-sshkey-key") {
        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {
            body()
        }
    }
}

return this
