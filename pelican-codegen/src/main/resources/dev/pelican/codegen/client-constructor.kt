    baseUrl: String = DEFAULT_BASE_URL,
    private val codecs: Codecs,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val timeout: Duration = Duration.ofSeconds(30),
    /**
     * Sent with every request. A function rather than a map because it is
     * called per request, which is what a short-lived token needs.
     */
    private val headers: () -> Map<String, String> = { emptyMap() },
