init {
    require(base.isNotEmpty()) {
        "This client has no base URL: the spec it was generated from declared no server. " +
            "Pass one: ${this::class.simpleName}(baseUrl = \"https://...\", codecs = ...)"
    }
}
