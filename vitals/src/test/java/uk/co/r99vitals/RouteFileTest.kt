package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** A route is written a fix at a time by one process and read back whole by another. */
class RouteFileTest {

    @get:Rule val folder = TemporaryFolder()

    private val start = 1_700_000_000_000L

    @Test fun `fixes come back as written, in order`() {
        val fixes = listOf(
            Route.Fix(start, 51.501364, -0.14189, 21.5, 4.0f),
            Route.Fix(start + 1_000, 51.501401, -0.141712, 21.7, 3.5f)
        )
        val route = RouteFile(folder.root, start)
        fixes.forEach(route::append)
        assertEquals(fixes, RouteFile(folder.root, start).fixes())
    }

    @Test fun `a fix without altitude or accuracy still reads`() {
        val route = RouteFile(folder.root, start)
        route.append(Route.Fix(start, 55.9533, -3.1883))
        assertEquals(listOf(Route.Fix(start, 55.9533, -3.1883)), route.fixes())
    }

    /** A line a crash cut off mid-write is the one thing that can be wrong in the file. */
    @Test fun `a line cut short is skipped, not the whole route`() {
        val file = File(folder.root, "$start.csv")
        file.writeText("$start,51.5,-0.14,20.0,5.0\n${start + 1_000},51.50")
        assertEquals(listOf(Route.Fix(start, 51.5, -0.14, 20.0, 5.0f)), RouteFile(file).fixes())
    }

    /** In a locale that writes 51,5 the fields would otherwise run into one another. */
    @Test fun `a comma decimal locale does not break the file`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val route = RouteFile(folder.root, start)
            route.append(Route.Fix(start, 48.137154, 11.576124, 519.0, 6.5f))
            assertEquals(listOf(Route.Fix(start, 48.137154, 11.576124, 519.0, 6.5f)), route.fixes())
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `the folder is made when the first fix arrives`() {
        val route = RouteFile(File(folder.root, "routes"), start)
        route.append(Route.Fix(start, 1.0, 2.0))
        assertEquals(1, route.fixes().size)
    }

    @Test fun `a deleted route is empty`() {
        val route = RouteFile(folder.root, start)
        route.append(Route.Fix(start, 1.0, 2.0))
        route.delete()
        assertEquals(emptyList<Route.Fix>(), route.fixes())
    }
}
