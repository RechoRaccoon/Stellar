package com.mediaviewer.util

/**
 * The calendar's built-in days: the major holidays and a long list of
 * smaller, more unusual ones (International Raccoon Appreciation Day,
 * National Be Nice Day, Pi Day …).
 *
 * Nothing is downloaded and no outside data set is bundled: a date is a
 * plain fact, so every day here is worked out on the device from a rule —
 * a fixed date, "the 4th Thursday of November", so many days from Easter —
 * or, for the holidays that follow a lunar calendar, from a small table of
 * years ([TABLE_YEARS]). Add or change a day by editing the lists below.
 */
object Holidays {
    class Day(val day: Int, val title: String, val major: Boolean)

    private sealed class Rule {
        /** The same month and day every year. */
        class Fixed(val month: Int, val day: Int) : Rule()
        /** The [n]th [weekday] (0 = Sunday … 6 = Saturday) of [month];
         *  n = -1 is the last one. [plus] moves on that many days. */
        class Nth(val month: Int, val weekday: Int, val n: Int, val plus: Int = 0) : Rule()
        /** [offset] days from Easter Sunday. */
        class Easter(val offset: Int) : Rule()
        /** Dates that follow another calendar: year → month * 100 + day. */
        class Table(val dates: Map<Int, Int>) : Rule()
    }

    private class Entry(val title: String, val major: Boolean, val rule: Rule)

    private fun major(title: String, rule: Rule) = Entry(title, true, rule)
    private fun minor(title: String, month: Int, day: Int) = Entry(title, false, Rule.Fixed(month, day))
    private fun minor(title: String, rule: Rule) = Entry(title, false, rule)

    private const val SUN = 0
    private const val MON = 1
    private const val WED = 3
    private const val THU = 4
    private const val FRI = 5
    private const val SAT = 6

    /** The years the lunar-calendar tables cover. */
    val TABLE_YEARS = 2026..2030

    private val MAJOR: List<Entry> = listOf(
        major("New Year's Day", Rule.Fixed(1, 1)),
        major("Martin Luther King Jr. Day", Rule.Nth(1, MON, 3)),
        major("Lunar New Year", Rule.Table(mapOf(2026 to 217, 2027 to 206, 2028 to 126, 2029 to 213, 2030 to 203))),
        major("Valentine's Day", Rule.Fixed(2, 14)),
        major("Presidents' Day", Rule.Nth(2, MON, 3)),
        major("Ramadan Begins", Rule.Table(mapOf(2026 to 218, 2027 to 208, 2028 to 128, 2029 to 116, 2030 to 106))),
        major("St. Patrick's Day", Rule.Fixed(3, 17)),
        major("Eid al-Fitr", Rule.Table(mapOf(2026 to 320, 2027 to 310, 2028 to 227, 2029 to 215, 2030 to 205))),
        major("Passover", Rule.Table(mapOf(2026 to 402, 2027 to 422, 2028 to 411, 2029 to 331, 2030 to 418))),
        major("Good Friday", Rule.Easter(-2)),
        major("Easter", Rule.Easter(0)),
        major("Mother's Day", Rule.Nth(5, SUN, 2)),
        major("Memorial Day", Rule.Nth(5, MON, -1)),
        major("Eid al-Adha", Rule.Table(mapOf(2026 to 527, 2027 to 517, 2028 to 505, 2029 to 424, 2030 to 413))),
        major("Juneteenth", Rule.Fixed(6, 19)),
        major("Father's Day", Rule.Nth(6, SUN, 3)),
        major("Independence Day", Rule.Fixed(7, 4)),
        major("Labor Day", Rule.Nth(9, MON, 1)),
        major("Rosh Hashanah", Rule.Table(mapOf(2026 to 912, 2027 to 1002, 2028 to 921, 2029 to 910, 2030 to 928))),
        major("Yom Kippur", Rule.Table(mapOf(2026 to 921, 2027 to 1011, 2028 to 930, 2029 to 919, 2030 to 1007))),
        major("Indigenous Peoples' Day", Rule.Nth(10, MON, 2)),
        major("Diwali", Rule.Table(mapOf(2026 to 1108, 2027 to 1029, 2028 to 1017, 2029 to 1105, 2030 to 1026))),
        major("Halloween", Rule.Fixed(10, 31)),
        major("Veterans Day", Rule.Fixed(11, 11)),
        major("Thanksgiving", Rule.Nth(11, THU, 4)),
        major("Hanukkah Begins", Rule.Table(mapOf(2026 to 1204, 2027 to 1224, 2028 to 1212, 2029 to 1201, 2030 to 1220))),
        major("Christmas Eve", Rule.Fixed(12, 24)),
        major("Christmas", Rule.Fixed(12, 25)),
        major("Kwanzaa Begins", Rule.Fixed(12, 26)),
        major("New Year's Eve", Rule.Fixed(12, 31))
    )

    private val MINOR: List<Entry> = listOf(
        // January
        minor("World Braille Day", 1, 4),
        minor("Appreciate a Dragon Day", 1, 16),
        minor("National Popcorn Day", 1, 19),
        minor("Penguin Awareness Day", 1, 20),
        minor("Squirrel Appreciation Day", 1, 21),
        minor("National Hugging Day", 1, 21),
        minor("National Compliment Day", 1, 24),
        minor("Opposite Day", 1, 25),
        minor("International Holocaust Remembrance Day", 1, 27),
        // February
        minor("Groundhog Day", 2, 2),
        minor("World Hedgehog Day", 2, 2),
        minor("World Nutella Day", 2, 5),
        minor("National Pizza Day", 2, 9),
        minor("International Day of Women and Girls in Science", 2, 11),
        minor("World Radio Day", 2, 13),
        minor("World Hippo Day", 2, 15),
        minor("Random Acts of Kindness Day", 2, 17),
        minor("Love Your Pet Day", 2, 20),
        minor("World Thinking Day", 2, 22),
        minor("International Polar Bear Day", 2, 27),
        minor("Pokémon Day", 2, 27),
        minor("Leap Day", 2, 29),
        minor("World Pangolin Day", Rule.Nth(2, SAT, 3)),
        minor("World Whale Day", Rule.Nth(2, SUN, 3)),
        minor("Mardi Gras", Rule.Easter(-47)),
        // March
        minor("Zero Discrimination Day", 3, 1),
        minor("World Wildlife Day", 3, 3),
        minor("International Women's Day", 3, 8),
        minor("Mario Day", 3, 10),
        minor("Pi Day", 3, 14),
        minor("International Day of Happiness", 3, 20),
        minor("World Frog Day", 3, 20),
        minor("World Sparrow Day", 3, 20),
        minor("World Poetry Day", 3, 21),
        minor("World Water Day", 3, 22),
        minor("National Puppy Day", 3, 23),
        minor("World Bear Day", 3, 23),
        minor("Purple Day", 3, 26),
        minor("Transgender Day of Visibility", 3, 31),
        minor("Manatee Appreciation Day", Rule.Nth(3, WED, -1)),
        // April
        minor("April Fools' Day", 4, 1),
        minor("World Autism Awareness Day", 4, 2),
        minor("World Rat Day", 4, 4),
        minor("World Health Day", 4, 7),
        minor("International Beaver Day", 4, 7),
        minor("National Unicorn Day", 4, 9),
        minor("National Siblings Day", 4, 10),
        minor("National Pet Day", 4, 11),
        minor("International Day of Human Space Flight", 4, 12),
        minor("National Dolphin Day", 4, 14),
        minor("World Art Day", 4, 15),
        minor("Save the Elephant Day", 4, 16),
        minor("Bat Appreciation Day", 4, 17),
        minor("Earth Day", 4, 22),
        minor("World Book Day", 4, 23),
        minor("World Penguin Day", 4, 25),
        minor("International Jazz Day", 4, 30),
        // May
        minor("May Day", 5, 1),
        minor("World Press Freedom Day", 5, 3),
        minor("Star Wars Day", 5, 4),
        minor("Cinco de Mayo", 5, 5),
        minor("International Day of Families", 5, 15),
        minor("International Day Against Homophobia, Biphobia and Transphobia", 5, 17),
        minor("World Bee Day", 5, 20),
        minor("International Tea Day", 5, 21),
        minor("International Day for Biological Diversity", 5, 22),
        minor("World Turtle Day", 5, 23),
        minor("Towel Day", 5, 25),
        minor("World Parrot Day", 5, 31),
        minor("World Otter Day", Rule.Nth(5, WED, -1)),
        // June
        minor("Pride Month Begins", 6, 1),
        minor("World Bicycle Day", 6, 3),
        minor("Hug Your Cat Day", 6, 4),
        minor("World Environment Day", 6, 5),
        minor("World Oceans Day", 6, 8),
        minor("World Blood Donor Day", 6, 14),
        minor("World Crocodile Day", 6, 17),
        minor("International Sushi Day", 6, 18),
        minor("World Refugee Day", 6, 20),
        minor("World Giraffe Day", 6, 21),
        minor("World Music Day", 6, 21),
        minor("International Day of Yoga", 6, 21),
        minor("World Camel Day", 6, 22),
        minor("National Donut Day", Rule.Nth(6, FRI, 1)),
        // July
        minor("Canada Day", 7, 1),
        minor("World UFO Day", 7, 2),
        minor("International Kissing Day", 7, 6),
        minor("World Chocolate Day", 7, 7),
        minor("World Population Day", 7, 11),
        minor("Shark Awareness Day", 7, 14),
        minor("Bastille Day", 7, 14),
        minor("World Snake Day", 7, 16),
        minor("World Emoji Day", 7, 17),
        minor("Nelson Mandela International Day", 7, 18),
        minor("International Moon Day", 7, 20),
        minor("National Junk Food Day", 7, 21),
        minor("International Tiger Day", 7, 29),
        minor("International Day of Friendship", 7, 30),
        // August
        minor("National Watermelon Day", 8, 3),
        minor("International Owl Awareness Day", 8, 4),
        minor("International Cat Day", 8, 8),
        minor("Book Lovers Day", 8, 9),
        minor("World Lion Day", 8, 10),
        minor("World Elephant Day", 8, 12),
        minor("International Left-Handers Day", 8, 13),
        minor("World Lizard Day", 8, 14),
        minor("Black Cat Appreciation Day", 8, 17),
        minor("World Photography Day", 8, 19),
        minor("World Mosquito Day", 8, 20),
        minor("International Dog Day", 8, 26),
        minor("World Honey Bee Day", Rule.Nth(8, SAT, 3)),
        // September
        minor("International Day of Charity", 9, 5),
        minor("Read a Book Day", 9, 6),
        minor("International Literacy Day", 9, 8),
        minor("World Suicide Prevention Day", 9, 10),
        minor("Positive Thinking Day", 9, 13),
        minor("Talk Like a Pirate Day", 9, 19),
        minor("International Day of Peace", 9, 21),
        minor("World Rhino Day", 9, 22),
        minor("Bi Visibility Day", 9, 23),
        minor("World Gorilla Day", 9, 24),
        minor("World Tourism Day", 9, 27),
        minor("World Heart Day", 9, 29),
        minor("International Red Panda Day", Rule.Nth(9, SAT, 3)),
        minor("International Rabbit Day", Rule.Nth(9, SAT, 4)),
        // October
        minor("International Raccoon Appreciation Day", 10, 1),
        minor("International Coffee Day", 10, 1),
        minor("International Day of Non-Violence", 10, 2),
        minor("World Animal Day", 10, 4),
        minor("National Be Nice Day", 10, 5),
        minor("World Teachers' Day", 10, 5),
        minor("World Octopus Day", 10, 8),
        minor("World Post Day", 10, 9),
        minor("World Mental Health Day", 10, 10),
        minor("National Coming Out Day", 10, 11),
        minor("International Day of the Girl", 10, 11),
        minor("World Food Day", 10, 16),
        minor("International Sloth Day", 10, 20),
        minor("Back to the Future Day", 10, 21),
        minor("United Nations Day", 10, 24),
        minor("World Pasta Day", 10, 25),
        minor("Intersex Awareness Day", 10, 26),
        minor("International Animation Day", 10, 28),
        minor("National Cat Day", 10, 29),
        minor("World Lemur Day", Rule.Nth(10, FRI, -1)),
        // November
        minor("World Vegan Day", 11, 1),
        minor("Día de los Muertos", 11, 2),
        minor("National Sandwich Day", 11, 3),
        minor("Guy Fawkes Night", 11, 5),
        minor("World Science Day", 11, 10),
        minor("World Kindness Day", 11, 13),
        minor("World Diabetes Day", 11, 14),
        minor("International Day for Tolerance", 11, 16),
        minor("International Men's Day", 11, 19),
        minor("Transgender Day of Remembrance", 11, 20),
        minor("World Hello Day", 11, 21),
        minor("Fibonacci Day", 11, 23),
        minor("International Jaguar Day", 11, 29),
        minor("Black Friday", Rule.Nth(11, THU, 4, plus = 1)),
        // December
        minor("World AIDS Day", 12, 1),
        minor("International Day of Persons with Disabilities", 12, 3),
        minor("International Cheetah Day", 12, 4),
        minor("National Cookie Day", 12, 4),
        minor("International Volunteer Day", 12, 5),
        minor("Pretend to Be a Time Traveler Day", 12, 8),
        minor("Human Rights Day", 12, 10),
        minor("International Mountain Day", 12, 11),
        minor("Monkey Day", 12, 14),
        minor("Festivus", 12, 23),
        minor("Boxing Day", 12, 26)
    )

    /** Easter Sunday of [year] (Gregorian), as days since 1970. */
    private fun easter(year: Int): Long {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return CalendarMath.daysFromCivil(year, month, day)
    }

    private fun keyOf(days: Long): Int {
        val (y, m, d) = CalendarMath.civilFromDays(days)
        return CalendarMath.key(y, m, d)
    }

    private fun resolve(rule: Rule, year: Int): Int? = when (rule) {
        is Rule.Fixed ->
            if (rule.day <= CalendarMath.daysInMonth(year, rule.month)) CalendarMath.key(year, rule.month, rule.day) else null
        is Rule.Nth -> {
            val count = CalendarMath.daysInMonth(year, rule.month)
            val first = CalendarMath.daysFromCivil(year, rule.month, 1)
            val lead = (rule.weekday - CalendarMath.weekday(first) + 7) % 7
            val day = if (rule.n > 0) 1 + lead + (rule.n - 1) * 7 else {
                var last = 1 + lead
                while (last + 7 <= count) last += 7
                last
            }
            if (day > count) null else keyOf(CalendarMath.daysFromCivil(year, rule.month, day) + rule.plus)
        }
        is Rule.Easter -> keyOf(easter(year) + rule.offset)
        is Rule.Table -> rule.dates[year]?.let { year * 10000 + it }
    }

    private val cache = HashMap<Int, Map<Int, List<Day>>>()

    /** Every built-in day of [year]: day (yyyymmdd) → what's on it. */
    private fun year(year: Int): Map<Int, List<Day>> = com.mediaviewer.platform.synchronizedCompat(cache) {
        cache.getOrPut(year) {
            val out = HashMap<Int, MutableList<Day>>()
            for (entry in MAJOR + MINOR) {
                val day = resolve(entry.rule, year) ?: continue
                out.getOrPut(day) { ArrayList() }.add(Day(day, entry.title, entry.major))
            }
            out
        }
    }

    /** What's on [day] (yyyymmdd), major first. */
    fun on(day: Int, major: Boolean, minor: Boolean): List<Day> {
        if (!major && !minor) return emptyList()
        return year(day / 10000)[day].orEmpty().filter { if (it.major) major else minor }.sortedByDescending { it.major }
    }

    /** The days in [year]/[month] that have something on them. */
    fun daysInMonth(year: Int, month: Int, major: Boolean, minor: Boolean): Set<Int> {
        if (!major && !minor) return emptySet()
        val out = HashSet<Int>()
        for ((day, list) in year(year)) {
            if (day / 100 % 100 == month && list.any { if (it.major) major else minor }) out.add(day)
        }
        return out
    }

    /** From [fromDay] on, soonest first, for about a year. */
    fun upcoming(fromDay: Int, major: Boolean, minor: Boolean): List<Day> {
        if (!major && !minor) return emptyList()
        val y = fromDay / 10000
        val until = (y + 1) * 10000 + fromDay % 10000
        return (year(y).values.flatten() + year(y + 1).values.flatten())
            .filter { it.day >= fromDay && it.day < until && (if (it.major) major else minor) }
            .sortedWith(compareBy({ it.day }, { !it.major }, { it.title }))
    }
}
