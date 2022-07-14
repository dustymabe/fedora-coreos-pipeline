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
