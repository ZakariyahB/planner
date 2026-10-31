package app.planner.assistant.llm

/** What the Worker's HTTP statuses mean, in short, plain words for the chat. Planning and the calendar work without the AI. */
object WorkerErrors {
    fun failureFor(status: Int): LlmFailure = when (status) {
        401 -> LlmFailure.SIGN_IN
        402 -> LlmFailure.CAP_REACHED
        413 -> LlmFailure.TOO_LONG
        429 -> LlmFailure.BUSY
        else -> LlmFailure.OTHER
    }

    fun messageFor(status: Int, body: String = ""): String = when (status) {
        401 -> "Your sign-in has expired. Sign in again to use the assistant."
        402 -> "Monthly AI cap reached (402)."
        413 -> "That conversation is too long (413). Clear the chat and try again."
        429 -> "The assistant is busy. Try again in a minute (429)."
        400 -> if ("unknown model" in body) {
            "The assistant model in Settings isn't one the server allows (400)."
        } else {
            "The assistant couldn't read that request (400)."
        }
        in 500..599 -> "The assistant is having trouble right now. Try again in a few minutes ($status)."
        else -> "The assistant is unavailable ($status)."
    }

    fun exceptionFor(status: Int, body: String = ""): LlmException = LlmException(failureFor(status), messageFor(status, body))
}
