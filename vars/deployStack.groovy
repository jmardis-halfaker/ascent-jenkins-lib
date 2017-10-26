/*
* Deploys a stack of Docker containers to the given Docker Swarm
*
*
*
*/
def call(body) {

    def config = [:]
    def vaultToken = null
    def stackName = env.JOB_NAME + '-' + env.BRANCH_NAME + '-' + env.BUILD_NUMBER
    def dockerFiles = ""
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (config.composeFiles == null) {
        error('No compose files defined for deployment')
    }
    if (config.dockerHost == null) {
        dockerHost = env.DOCKER_SWARM_MANAGER
    }

    for (file in config.composeFiles) {
        if (fileExists(file)) {
            dockerFiles = dockerFiles + "-c " + file + " "
        } else {
            error(file + 'was not found')
        }
    }

    stage("Requesting Vault Token for application") {
        withCredentials([string(credentialsId: 'jenkins-vault', variable: 'JENKINS_VAULT_TOKEN')]) {
            vaultToken = sh(returnStdout: true, script: "curl -k -s --header \"X-Vault-Token: ${JENKINS_VAULT_TOKEN}\" --request POST --data '{\"display_name\": \"testenv\"}' ${env.VAULT_ADDR}/v1/auth/token/create/ascent | jq '.auth.client_token'").trim()
        }
    }

    stage("Deploying Stack: ${stackName}") {
        withEnv(["VAULT_TOKEN=${vaultToken}"]) {
            sh "docker stack deploy --host ${config.dockerHost} ${dockerFiles} ${stackName}"
        }

        //Query docker every minute to see if deployment is complete
        echo 'Wating for containers to finish deploying...'
        timeout(time: 10, unit: 'MINUTES') {
            def deployDone = false
            waitUntil(deployDone) {
                sleep(60)
                def result = sh(returnStdout: true, script: "docker stack ps ${stackName} --format {{.CurrentState}}")
                deployDone = !(result.contains('Failed') || result.contains('Preparing') || result.contains('Starting'))
            }
        }
        echo 'Containers are successfully deployed'
    }
    
}