package org.example.review

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

class GitHubPublicationException(message: String) : IllegalStateException(message)

class GitHubReviewPublisher(
    private val httpClient: HttpClient,
    private val token: String,
    repository: String,
    private val pullRequestNumber: Int,
    private val apiBase: String = "https://api.github.com",
) : ReviewCommentPublisher {
    private val repository = validateRepository(repository)

    init {
        require(token.isNotBlank()) { "GITHUB_TOKEN не задан" }
        require(pullRequestNumber > 0) { "PR number должен быть положительным" }
    }

    override suspend fun upsert(markdown: String) {
        require(AI_REVIEW_COMMENT_MARKER in markdown) { "Review comment не содержит marker" }
        val matching = listComments().filter {
            AI_REVIEW_COMMENT_MARKER in it.body && it.user.login == GITHUB_ACTIONS_BOT
        }.sortedBy(GitHubComment::id)
        if (matching.isEmpty()) {
            ensureSuccess(httpClient.post("$apiBase/repos/$repository/issues/$pullRequestNumber/comments") {
                githubHeaders()
                contentType(ContentType.Application.Json)
                setBody(CommentBody(markdown))
            }, "create review comment")
        } else {
            ensureSuccess(httpClient.patch("$apiBase/repos/$repository/issues/comments/${matching.first().id}") {
                githubHeaders()
                contentType(ContentType.Application.Json)
                setBody(CommentBody(markdown))
            }, "update review comment")
            matching.drop(1).forEach { duplicate ->
                ensureSuccess(httpClient.delete("$apiBase/repos/$repository/issues/comments/${duplicate.id}") {
                    githubHeaders()
                }, "remove duplicate review comment")
            }
        }
    }

    private suspend fun listComments(): List<GitHubComment> {
        val result = mutableListOf<GitHubComment>()
        for (page in 1..10) {
            val response = httpClient.get("$apiBase/repos/$repository/issues/$pullRequestNumber/comments?per_page=100&page=$page") {
                githubHeaders()
            }
            ensureSuccess(response, "list pull request comments")
            val comments = response.body<List<GitHubComment>>()
            result += comments
            if (comments.size < 100) break
        }
        return result
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header(HttpHeaders.UserAgent, "llm-workbench-ai-code-review")
    }

    private fun ensureSuccess(response: HttpResponse, action: String) {
        if (!response.status.isSuccess()) {
            throw GitHubPublicationException("GitHub could not $action (HTTP ${response.status.value}); response body was not logged")
        }
    }

    private fun validateRepository(value: String): String = value.also {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it)) { "GitHub repository должен иметь формат owner/name" }
    }

    @Serializable
    private data class CommentBody(val body: String)

    @Serializable
    private data class GitHubComment(val id: Long, val body: String, val user: GitHubUser)

    @Serializable
    private data class GitHubUser(val login: String)

    companion object {
        private const val GITHUB_ACTIONS_BOT = "github-actions[bot]"
    }
}
