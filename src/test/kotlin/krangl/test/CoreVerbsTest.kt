package krangl.test

import io.kotlintest.matchers.have
import io.kotlintest.specs.FlatSpec
import krangl.*
import org.apache.commons.csv.CSVFormat


val sleepData = DataFrame.fromCSV(DataFrame::class.java.getResourceAsStream("data/msleep.csv"))
val irisData = DataFrame.fromCSV(DataFrame::class.java.getResourceAsStream("data/iris.txt"), format = CSVFormat.TDF)
val flights = DataFrame.fromCSV(DataFrame::class.java.getResourceAsStream("data/nycflights.tsv.gz"), format = CSVFormat.TDF, isCompressed = true)


class SelectTest : FlatSpec() { init {

    "it" should "select with regex" {
        sleepData.select({ endsWith("wt") }).ncol shouldBe 2
        sleepData.select({ endsWith("wt") }).ncol shouldBe 2
        sleepData.select({ startsWith("sleep") }).ncol shouldBe 3
        sleepData.select({ oneOf("conservation", "foobar", "order") }).ncol shouldBe 2
    }


    "it" should "select non-existing column" {
        try {
            sleepData.select("foobar")
            fail("foobar should not be selectable")
        } catch(t: Throwable) {
            // todo expect more descriptive exception here. eg. ColumnDoesNotExistException
        }
    }


    "it" should "select no columns" {
        try {
            sleepData.select(listOf())
            fail("should complain about mismatching selector array dimensionality")
        } catch(t: Throwable) {
        }

        sleepData.select(*arrayOf<String>()).ncol shouldBe 0
    }


    "it" should "select same columns twice" {
        // double selection is flattend out as in dplyr:  iris %>% select(Species, Species) %>% glimpse

        shouldThrow<IllegalArgumentException> {
            sleepData.select("name", "vore", "name").ncol shouldBe 2
        }

        sleepData.select("name", "vore").ncol shouldBe 2
    }


    "it" should "do a negative selection" {
        sleepData.select(-"name", -"vore").apply {
            names.contains("name") shouldBe false
            names.contains("vore") shouldBe false

            // ensure preserved order of remaining columns
            sleepData.names.minus(arrayOf("name", "vore")) shouldEqual names
        }

    }

    // krangl should prevent that negative and positive selections are combined in a single select() statement
    "it" should "do combined negative and positive selection" {
        // cf.  iris %>% select(ends_with("Length"), - Petal.Length) %>% glimpse()
        // not symmetric:  iris %>% select(- Petal.Length, ends_with("Length")) %>% glimpse()
        //  iris %>% select(-Petal.Length, ends_with("Length")) %>% glimpse()
        irisData.select({ endsWith("Length") }, -"Petal.Length").apply {
            names shouldEqual listOf("Sepal.Length")
        }
    }
}
}


class MutateTest : FlatSpec() { init {
    "it" should "rename columns and preserve their positions" {
        sleepData.rename("vore" to "new_vore", "awake" to "awa2").apply {
            glimpse()
            names.contains("vore") shouldBe false
            names.contains("new_vore") shouldBe true

            // column renaming should preserve positions
            names.indexOf("new_vore") shouldEqual sleepData.names.indexOf("vore")

            // renaming should not affect column or row counts
            nrow == sleepData.nrow
            ncol == sleepData.ncol
        }
    }

    "it" should "allow to use a new column in the same mutate call" {
        sleepData.mutate(
                "vore_new" to { it["vore"] },
                "vore_first_char" to { it["vore"].asStrings().ignoreNA { this.toList().first().toString() } }
        )
    }
}
}


class FilterTest : FlatSpec() { init {
    "it" should "head tail and slic should extract data as expextd" {
        // todo test that the right portions were extracted and not just size
        sleepData.head().nrow shouldBe  5
        sleepData.tail().nrow shouldBe  5
        sleepData.slice(1, 3, 5).nrow shouldBe  3
    }

    "it" should "filter in empty table" {
        sleepData
                .filter { it["name"] eq "foo" }
                // refilter on empty one
                .filter { it["name"] eq "bar" }
    }
}
}


class SummarizeTest : FlatSpec() { init {
    "it" should "fail if summaries are not scalar values" {
        shouldThrow<NonScalarValueException> {
            sleepData.summarize("foo", { listOf("a", "b", "c") })
        }
        shouldThrow<NonScalarValueException> {
            sleepData.summarize("foo", { BooleanArray(12) })
        }

    }

    "it" should "should allow complex objects as summaries" {
        class Something {
            override fun toString(): String = "Something(${hashCode()}"
        }

        sleepData.groupBy("vore").summarize("foo" to { Something() }, "bar" to { Something() }).print()
    }
}
}


class EmptyTest : FlatSpec() { init {
    "it" should "handle  empty (row and column-empty) data-frames in all operations" {
        SimpleDataFrame().apply {
            // structure
            ncol shouldBe 0
            nrow shouldBe 0
            rows.toList() should have size 0
            cols.toList() should have size 0

            // rendering
            glimpse()
            print()

            select(emptyList()) // will output warning
            // core verbs
            filter { BooleanArray(0) }
            mutate("foo", { "bar" })
            summarize("foo" to { "bar" })
            arrange()

            // grouping
            (groupBy() as GroupedDataFrame).groups()
        }
    }
}
}

class GroupedDataTest : FlatSpec() { init {

    /** dplyr considers NA as a group and krangl should do the same

    ```
    require(dplyr)

    iris
    iris$Species[1] <- NA

    ?group_by
    grpdIris <- group_by(iris, Species)
    grpdIris %>% slice(1)
    ```
     */
    "it" should "allow for NA as a group value" {

        // 1) test single attribute grouping with NA
        (sleepData.groupBy("vore") as GroupedDataFrame).groups().nrow shouldBe 5

        // 2) test multi-attribute grouping with NA in one or all attributes
//        (sleepData.groupBy("vore") as GroupedDataFrame).groups().nrow shouldBe 6
        //todo implement me
    }


    "it" should "count group sizes and report distinct rows in a table" {
        // 1) test single attribute grouping with NA
        sleepData.count("vore").apply {
            print()
            ncol shouldBe 2
            nrow shouldBe 5
        }

        sleepData.distinct("vore", "order").apply {
            print()
            nrow shouldBe 32
            ncol shouldBe 11
        }
    }


    "it" should "should auto-select grouping attributes from a grouped dataframe"{
//        flights.glimpse()
        val subFlights = flights
                .groupBy("year", "month", "day")
//                .select({ range("year", "day") }, { oneOf("arr_delay", "dep_delay") })
                .select("arr_delay", "dep_delay", "year")

        subFlights.apply {
            ncol shouldBe 5
            (this is GroupedDataFrame) shouldBe true
            (this as GroupedDataFrame).groups.toList().first().df.ncol shouldBe 5
        }

    }


    "it" should "calculate same group hash irrespective of column order"{
//        flights.glimpse()

        var dfA: DataFrame = dataFrameOf(
                "first_name", "last_name", "age", "weight")(
                "Max", "Doe", 23, 55,
                "Franz", "Smith", 23, 88,
                "Horst", "Keanes", 12, 82
        )

        val dfB = dfA.select("age", "last_name", "weight", "first_name")

        // by joining with multiple attributes we inherentily group (which is the actual test
        val dummyJoin = leftJoin(dfA, dfB, by = listOf("last_name", "first_name"))

        dummyJoin.apply {
            nrow shouldBe 3
        }
    }

}
}

