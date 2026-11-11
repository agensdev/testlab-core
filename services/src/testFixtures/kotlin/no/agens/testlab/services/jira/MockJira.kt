package no.agens.testlab.services.jira

import no.agens.testlab.services.builds.MockGitHub

/**
 * A scripted example.atlassian.net with one ticket, SHOP-1234: cleared for a devtest,
 * assigned to a developer, with a linked PR in the development panel. Writes answer like Jira.
 */
class MockJira {
    val http = MockGitHub()
    val seen get() = http.seen
    val client get() = http.client

    fun json(route: String, body: String, status: Int = 200) = http.json(route, body, status)

    init {
        json("GET $API/myself", """{"accountId":"me-1","displayName":"Test Testesen","emailAddress":"test@example.com"}""")
        json("GET $API/issue/SHOP-1234", ISSUE)
        json("POST $API/search/jql", """{"issues":[$ISSUE],"isLast":true}""")
        json("POST $API/issue/SHOP-1234/comment", """{"id":"10500"}""", 201)
        json("GET $API/attachment/meta", """{"enabled":true,"uploadLimit":104857600}""")
        json("POST $API/issue/SHOP-1234/attachments", """[{"id":"20001","filename":"report-standalone.html","mimeType":"text/html","size":1234,"created":"2026-10-02T10:00:00.000+0200","author":{"accountId":"me-1","displayName":"Test Testesen"}}]""")
        json("PUT $API/issue/SHOP-1234/assignee", "", 204)
        json("PUT $API/issue/SHOP-1234", "", 204)
        json("GET $SITE/rest/dev-status/latest/issue/summary", """{"summary":{"pullrequest":{"byInstanceType":{"GitHub":{"count":1}}},"branch":{"byInstanceType":{}}}}""")
        json("GET $SITE/rest/dev-status/latest/issue/detail", """{"detail":[{"pullRequests":[{"id":"#4321","name":"SHOP-1234: New cart view","url":"https://github.com/example-org/shop-android/pull/4321","status":"OPEN","source":{"branch":"feature/SHOP-1234-cart"}}],"branches":[]}]}""")
    }

    companion object {
        const val SITE = "https://example.atlassian.net"
        const val API = "$SITE/rest/api/3"
        val ISSUE = """
            {"id":"77001","key":"SHOP-1234","fields":{
              "summary":"New cart view","issuetype":{"name":"Story"},
              "status":{"name":"Til test","statusCategory":{"key":"indeterminate"}},
              "priority":{"name":"Medium"},
              "assignee":{"accountId":"dev-9","displayName":"Utvikler Utviklersen"},
              "reporter":{"accountId":"po-2","displayName":"Produkt Eier"},
              "labels":["devtest","android"],"components":[{"name":"Cart"}],"fixVersions":[{"name":"5.12"}],
              "created":"2026-09-20T09:00:00.000+0200","updated":"2026-10-01T15:00:00.000+0200",
              "description":{"type":"doc","version":1,"content":[
                {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Akseptansekriterier"}]},
                {"type":"bulletList","content":[
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"The cart viser "},{"type":"text","text":"items","marks":[{"type":"strong"}]}]}]},
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Se "},{"type":"text","text":"Figma","marks":[{"type":"link","attrs":{"href":"https://figma.com/x"}}]}]}]}
                ]}
              ]},
              "comment":{"total":1,"comments":[{"id":"1","author":{"displayName":"Utvikler Utviklersen"},"created":"2026-10-01T15:00:00.000+0200","body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Klar for test på INT"}]}]}}]},
              "attachment":[],"issuelinks":[{"type":{"inward":"is blocked by","outward":"blocks"},"outwardIssue":{"key":"SHOP-1300"}}]
            }}
        """.trimIndent()
    }
}
