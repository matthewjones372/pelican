package io.github.matthewjones372.pelican.health

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test

class JdbcCheckTest {

    private val pool = JdbcConnectionPool.create("jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1", "sa", "")

    @Test
    fun `a pool that hands out a valid connection passes`() {
        try {
            jdbc(pool) shouldBe Status.Pass
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `a pool that has been closed fails`() {
        pool.dispose()

        jdbc(pool).shouldBeInstanceOf<Status.Fail>()
    }
}
