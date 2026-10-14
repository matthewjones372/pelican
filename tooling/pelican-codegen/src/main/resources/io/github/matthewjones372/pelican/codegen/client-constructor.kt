    baseUrl: String = DEFAULT_BASE_URL,
    private val codecs: Codecs,
    /**
     * Where a built request goes. The default is whichever `ClientTransport`
     * the classpath supplies — `pelican-client-pekko`, over Pekko HTTP's
     * client, unless the build says otherwise — so a service that already
     * runs and tunes an HTTP client can hand that one over rather than acquire
     * a second HTTP stack because it generated a client.
     */
    private val transport: ClientTransport = ClientTransport.default(),
    private val timeout: Duration = Duration.ofSeconds(30),
    /**
     * Sent with every request. A function rather than a map because it is
     * called per request, which is what a short-lived token needs.
     */
    private val headers: () -> Map<String, String> = { emptyMap() },
