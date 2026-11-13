package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.autopilot.AutopilotError
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.secrets.CredentialResolver
import no.agens.testlab.services.secrets.MemorySecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestPoolsTest {
    private val pool = Profile.Pool(
        name = "testpool",
        url = "https://api.\${env}.example.com/pool/fetch",
        headers = mapOf("apiKey" to "\${key}"),
        fields = mapOf("user_id" to "personId", "name" to "profile.name"),
        id = "user_id",
    )
    private val calls = mutableListOf<Triple<String, String, Map<String, String>>>()

    private fun pools(answer: Pair<Int, String>, key: String? = "s3cret-key") =
        TestPools({ listOf(pool) }, MemorySecretStore(key?.let { mapOf("pool.testpool.key" to it) } ?: emptyMap()), TestPools.Http { m, u, h -> calls += Triple(m, u, h); answer })

    @Test
    fun fetchesAUserForTheEnvironmentWithTheKey() = runBlocking {
        val p = pools(200 to """{"personId": 4711, "profile": {"name": "Vesle"}}""")
        val u = p.take(pool, "int", "phone1")
        assertEquals(Triple("GET", "https://api.int.example.com/pool/fetch", mapOf("apiKey" to "s3cret-key")), calls.single())
        assertEquals(mapOf("user_id" to "4711", "name" to "Vesle"), u.values)
        assertEquals("4711", u.id)
        assertEquals("4711", p.value("testpool.user_id", "phone1"))
    }

    @Test
    fun aListAnswerGivesOneOfItsUsers() = runBlocking {
        val u = pools(200 to """[{"personId": 1, "profile": {"name": "a"}}, {"personId": 2, "profile": {"name": "b"}}]""").fetch(pool, "stage")
        assertTrue(u.id in setOf("1", "2"))
    }

    @Test
    fun usersArePerDeviceAndPinnedOnesSkipTheService() = runBlocking {
        val p = pools(200 to """{"personId": 9, "profile": {"name": "n"}}""")
        p.take(pool, "int", "phone1")
        p.take(pool, "int", "phone2", pinned = "123")
        assertEquals(1, calls.size)
        assertEquals("9", p.value("testpool.user_id", "phone1"))
        assertEquals("123", p.value("testpool.user_id", "phone2"))
        assertTrue("handsets_login" in assertFailsWith<AutopilotError> { p.value("testpool.user_id", "phone3") }.message!!)
        assertEquals(null, p.value("demo.password", "phone1")) // not a pool: the vault answers
    }

    @Test
    fun problemsAreExplainedWithoutTheKey() = runBlocking {
        assertTrue("testlab secrets set pool.testpool.key" in assertFailsWith<IllegalStateException> { pools(200 to "{}", key = null).fetch(pool, "int") }.message!!)
        val refused = assertFailsWith<IllegalStateException> { pools(401 to "no").fetch(pool, "int") }.message!!
        assertTrue("refused" in refused && "s3cret-key" !in refused)
        val missing = assertFailsWith<IllegalStateException> { pools(200 to """{"personId": 1}""").fetch(pool, "int") }.message!!
        assertTrue("profile.name" in missing, missing)
    }

    @Test
    fun theResolverAsksThePoolFirstAndKnowsItsRefs() = runBlocking {
        val store = MemorySecretStore(mapOf("account.demo.password" to "pw", "pool.testpool.key" to "k"))
        val p = TestPools({ listOf(pool) }, store, TestPools.Http { _, _, _ -> 200 to """{"personId": 5, "profile": {"name": "x"}}""" })
        val r = CredentialResolver(store).apply { source = p }
        p.take(pool, "int", "s1")
        assertEquals("5", r.resolve("testpool.user_id", "s1"))
        assertEquals("pw", r.resolve("demo.password", "s1"))
        assertTrue("testpool.user_id" in r.knownRefs() && "demo.password" in r.knownRefs())
    }
}
