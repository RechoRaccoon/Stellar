package com.mediaviewer.util

/**
 * The calendar's long tail of fun days (National Nut Day, Trivia Day,
 * International Caps Lock Day…): one a line, "MM-DD Name", every one on the
 * same date each year. Kept as plain blocks of data (a season each), not
 * a call per day, so the list can be as long as the year is full of them.
 * They're shown under their English names (see Holidays.MORE).
 */
internal val MORE_FUN_DAYS: List<String> = listOf(
    """
01-01 National Bloody Mary Day
01-01 Polar Bear Plunge Day
01-01 Public Domain Day
01-02 National Science Fiction Day
01-02 National Cream Puff Day
01-02 National Motivation and Inspiration Day
01-03 J.R.R. Tolkien Day
01-03 Festival of Sleep Day
01-03 National Drinking Straw Day
01-03 National Chocolate Covered Cherry Day
01-04 Trivia Day
01-04 National Hypnotism Day
01-05 National Whipped Cream Day
01-05 National Screenwriters Day
01-06 National Bean Day
01-06 National Technology Day
01-06 Cuddle Up Day
01-07 National Tempura Day
01-07 Old Rock Day
01-07 National Bobblehead Day
01-08 National Bubble Bath Day
01-08 Earth's Rotation Day
01-08 National Joy Germ Day
01-09 National Static Electricity Day
01-09 National Apricot Day
01-09 Law Enforcement Appreciation Day
01-10 National Houseplant Appreciation Day
01-10 Peculiar People Day
01-10 National Cut Your Energy Costs Day
01-11 National Milk Day
01-11 Learn Your Name in Sign Language Day
01-11 National Human Trafficking Awareness Day
01-12 National Pharmacist Day
01-12 Kiss a Ginger Day
01-12 National Marzipan Day
01-13 Rubber Duckie Day
01-13 Korean American Day
01-13 Make Your Dreams Come True Day
01-14 National Dress Up Your Pet Day
01-14 Organize Your Home Day
01-15 Wikipedia Day
01-15 National Strawberry Ice Cream Day
01-16 National Nothing Day
01-16 National Religious Freedom Day
01-16 International Hot and Spicy Food Day
01-17 Kid Inventors' Day
01-17 Benjamin Franklin Day
01-17 Ditch New Year's Resolutions Day
01-17 National Hot Buttered Rum Day
01-18 Thesaurus Day
01-18 National Gourmet Coffee Day
01-19 National Tin Can Day
01-20 National Cheese Lover's Day
01-20 National DJ Day
01-20 National Buttercrunch Day
01-21 National Granola Bar Day
01-21 One-Liner Day
01-22 National Blonde Brownie Day
01-22 Celebration of Life Day
01-23 National Handwriting Day
01-23 Measure Your Feet Day
01-24 Global Belly Laugh Day
01-24 Beer Can Appreciation Day
01-25 National Irish Coffee Day
01-25 Burns Night
01-25 Fun at Work Day
01-26 Australia Day
01-26 National Peanut Brittle Day
01-26 National Spouses Day
01-27 National Chocolate Cake Day
01-27 Thomas Crapper Day
01-27 National Geography Day
01-28 National Kazoo Day
01-28 Data Privacy Day
01-28 National Blueberry Pancake Day
01-29 National Corn Chip Day
01-29 Freethinkers Day
01-30 National Croissant Day
01-30 Inane Answering Message Day
01-31 Backward Day
01-31 Inspire Your Heart with Art Day
01-31 National Gorilla Suit Day
02-01 National Freedom Day
02-01 Change Your Password Day
02-01 National Baked Alaska Day
02-02 National Tater Tot Day
02-02 World Wetlands Day
02-03 National Carrot Cake Day
02-03 The Day the Music Died
02-03 National Golden Retriever Day
02-04 World Cancer Day
02-04 Rosa Parks Day
02-04 National Homemade Soup Day
02-04 National Stuffed Mushroom Day
02-05 National Weatherperson's Day
02-05 National Chocolate Fondue Day
02-06 National Frozen Yogurt Day
02-06 Lame Duck Day
02-06 Waitangi Day
02-07 National Send a Card to a Friend Day
02-07 National Periodic Table Day
02-07 Charles Dickens Day
02-08 National Kite Flying Day
02-08 Opera Day
02-08 National Boy Scouts Day
02-09 Toothache Day
02-09 Read in the Bathtub Day
02-10 National Umbrella Day
02-10 National Cream Cheese Brownie Day
02-11 National Inventors' Day
02-11 National Make a Friend Day
02-11 White T-Shirt Day
02-12 Darwin Day
02-12 National Lost Penny Day
02-12 National Plum Pudding Day
02-13 National Tortellini Day
02-13 National Cheddar Day
02-14 National Organ Donor Day
02-14 Ferris Wheel Day
02-14 National Cream-Filled Chocolates Day
02-15 National Gumdrop Day
02-15 Singles Awareness Day
02-15 National Flag of Canada Day
02-16 National Almond Day
02-16 Do a Grouch a Favor Day
02-17 National Café au Lait Day
02-17 My Way Day
02-18 National Drink Wine Day
02-18 Pluto Day
02-18 National Battery Day
02-19 National Chocolate Mint Day
02-19 National Lash Day
02-20 National Cherry Pie Day
02-20 World Day of Social Justice
02-21 National Sticky Bun Day
02-21 International Mother Language Day
02-21 Card Reading Day
02-22 National Cook a Sweet Potato Day
02-22 Be Humble Day
02-22 Walking the Dog Day
02-23 National Banana Bread Day
02-23 International Dog Biscuit Appreciation Day
02-23 Curling Is Cool Day
02-24 National Tortilla Chip Day
02-24 World Bartender Day
02-25 National Clam Chowder Day
02-25 Let's All Eat Right Day
02-26 Tell a Fairy Tale Day
02-26 Levi Strauss Day
02-27 No Brainer Day
02-28 National Chocolate Soufflé Day
02-28 Floral Design Day
02-28 Public Sleeping Day
03-01 National Peanut Butter Lover's Day
03-01 World Compliment Day
03-02 National Banana Cream Pie Day
03-02 Old Stuff Day
03-03 National Mulled Wine Day
03-03 I Want You to Be Happy Day
03-03 World Hearing Day
03-04 National Pound Cake Day
03-04 National Sons Day
03-05 National Cheese Doodle Day
03-05 Multiple Personality Day
03-06 National Dentist's Day
03-06 National Frozen Food Day
03-06 National White Chocolate Cheesecake Day
03-07 National Be Heard Day
03-08 National Peanut Cluster Day
03-08 Proofreading Day
03-09 National Meatball Day
03-09 National Crabmeat Day
03-09 Barbie Day
03-10 National Pack Your Lunch Day
03-10 International Day of Awesomeness
03-11 Johnny Appleseed Day
03-11 World Plumbing Day
03-12 National Girl Scout Day
03-12 National Baked Scallops Day
03-12 Plant a Flower Day
03-13 National Good Samaritan Day
03-13 Ear Muff Day
03-13 National Jewel Day
03-14 National Potato Chip Day
03-14 Learn About Butterflies Day
03-15 World Consumer Rights Day
03-15 The Ides of March
03-15 Everything You Think Is Wrong Day
03-16 National Artichoke Hearts Day
03-16 Freedom of Information Day
03-16 Lips Appreciation Day
03-17 National Corned Beef and Cabbage Day
03-17 Submarine Day
03-18 National Awkward Moments Day
03-18 National Sloppy Joe Day
03-19 National Let's Laugh Day
03-19 National Poultry Day
03-19 National Chocolate Caramel Day
03-20 National Ravioli Day
03-20 Proposal Day
03-20 Snowman Burning Day
03-21 National Single Parent Day
03-21 National French Bread Day
03-21 World Puppetry Day
03-22 National Goof Off Day
03-22 International Day of the Seal
03-23 National Chip and Dip Day
03-23 Near Miss Day
03-23 World Meteorological Day
03-24 National Chocolate Covered Raisins Day
03-24 World Tuberculosis Day
03-25 National Pecan Day
03-25 Tolkien Reading Day
03-25 National Medal of Honor Day
03-25 Waffle Day
03-26 National Spinach Day
03-26 Live Long and Prosper Day
03-27 National Joe Day
03-27 World Theatre Day
03-27 National Spanish Paella Day
03-28 National Black Forest Cake Day
03-28 National Something on a Stick Day
03-28 Respect Your Cat Day
03-29 National Mom and Pop Business Owners Day
03-29 National Lemon Chiffon Cake Day
03-29 Smoke and Mirrors Day
03-30 National Doctors' Day
03-30 National Pencil Day
03-30 National Take a Walk in the Park Day
03-31 National Crayon Day
03-31 National Clams on the Half Shell Day
03-31 World Backup Day
03-31 César Chávez Day
""",
    """
04-01 National Sourdough Bread Day
04-01 National Fun Day
04-02 International Children's Book Day
04-03 National Chocolate Mousse Day
04-03 National Find a Rainbow Day
04-03 World Party Day
04-03 Don't Go to Work Unless It's Fun Day
04-04 National Vitamin C Day
04-04 National Hug a Newsperson Day
04-04 International Carrot Day
04-05 National Deep Dish Pizza Day
04-05 National Caramel Day
04-05 Go for Broke Day
04-06 National Caramel Popcorn Day
04-06 National Student-Athlete Day
04-06 National Tartan Day
04-06 National Teflon Day
04-07 National Coffee Cake Day
04-07 No Housework Day
04-08 National Zoo Lovers Day
04-08 Draw a Picture of a Bird Day
04-08 National Empanada Day
04-09 National Name Yourself Day
04-09 Winston Churchill Day
04-10 National Farm Animals Day
04-10 National Cinnamon Crescent Day
04-10 Encourage a Young Writer Day
04-11 National Cheese Fondue Day
04-11 Barbershop Quartet Day
04-11 National Eight-Track Tape Day
04-12 National Licorice Day
04-12 Big Wind Day
04-13 National Scrabble Day
04-13 National Peach Cobbler Day
04-13 Thomas Jefferson Day
04-14 Look Up at the Sky Day
04-14 National Gardening Day
04-14 International Moment of Laughter Day
04-15 Tax Day
04-15 McDonald's Day
04-15 Titanic Remembrance Day
04-16 National Wear Your Pajamas to Work Day
04-16 National Eggs Benedict Day
04-17 National Cheeseball Day
04-17 National Haiku Poetry Day
04-17 Blah Blah Blah Day
04-18 National Animal Crackers Day
04-18 Pet Owners Independence Day
04-18 International Day for Monuments and Sites
04-19 National Garlic Day
04-19 National Hanging Out Day
04-19 National Rice Ball Day
04-19 Bicycle Day
04-20 National Pineapple Upside-Down Cake Day
04-20 National Look-Alike Day
04-20 Volunteer Recognition Day
04-21 National Chocolate-Covered Cashews Day
04-21 National Kindergarten Day
04-21 Big Word Day
04-21 World Creativity and Innovation Day
04-22 National Jelly Bean Day
04-23 National Picnic Day
04-23 National Lost Dog Awareness Day
04-23 Talk Like Shakespeare Day
04-23 English Language Day
04-24 National Pigs in a Blanket Day
04-24 National Skipping Day
04-25 National DNA Day
04-25 National Telephone Day
04-25 World Malaria Day
04-25 National Zucchini Bread Day
04-26 National Kids and Pets Day
04-26 National Help a Horse Day
04-26 Richter Scale Day
04-27 National Prime Rib Day
04-27 National Tell a Story Day
04-27 Morse Code Day
04-28 National Blueberry Pie Day
04-28 National Superhero Day
04-28 Workers' Memorial Day
04-28 Great Poetry Reading Day
04-29 National Shrimp Scampi Day
04-29 National Zipper Day
04-29 Peace Rose Day
04-30 National Honesty Day
04-30 National Oatmeal Cookie Day
04-30 Bugs Bunny Day
04-30 National Adopt a Shelter Pet Day
05-01 Mother Goose Day
05-01 Lei Day
05-01 National Chocolate Parfait Day
05-01 Loyalty Day
05-02 National Truffle Day
05-02 Brothers and Sisters Day
05-02 World Tuna Day
05-02 Baby Day
05-03 National Paranormal Day
05-03 National Raspberry Tart Day
05-03 Lumpy Rug Day
05-04 National Weather Observers Day
05-04 National Orange Juice Day
05-04 International Firefighters' Day
05-04 Bird Day
05-05 National Hoagie Day
05-05 National Astronaut Day
05-06 Revenge of the Sixth
05-06 International No Diet Day
05-06 National Crêpes Suzette Day
05-06 Beverage Day
05-07 National Roast Leg of Lamb Day
05-08 National Coconut Cream Pie Day
05-08 Iris Day
05-08 World Red Cross Day
05-09 National Butterscotch Brownie Day
05-09 National Lost Sock Memorial Day
05-09 Europe Day
05-10 National Clean Up Your Room Day
05-10 National Shrimp Day
05-10 Trust Your Intuition Day
05-11 National Eat What You Want Day
05-11 Twilight Zone Day
05-12 Limerick Day
05-12 National Nutty Fudge Day
05-12 International Nurses Day
05-13 National Apple Pie Day
05-13 Frog Jumping Day
05-13 National Leprechaun Day
05-14 National Buttermilk Biscuit Day
05-14 National Underground America Day
05-15 National Chocolate Chip Day
05-15 National Nylon Stockings Day
05-16 National Barbecue Day
05-16 National Love a Tree Day
05-16 Wear Purple for Peace Day
05-16 International Day of Light
05-17 National Pack Rat Day
05-17 National Cherry Cobbler Day
05-17 World Baking Day
05-17 World Telecommunication Day
05-18 National Visit Your Relatives Day
05-18 National Cheese Soufflé Day
05-19 National May Ray Day
05-19 National Devil's Food Cake Day
05-19 Malcolm X Day
05-20 National Quiche Lorraine Day
05-20 Be a Millionaire Day
05-20 Pick Strawberries Day
05-20 World Metrology Day
05-21 National Strawberries and Cream Day
05-21 I Need a Patch for That Day
05-22 National Maritime Day
05-22 National Vanilla Pudding Day
05-22 Sherlock Holmes Day
05-22 Harvey Milk Day
05-23 National Lucky Penny Day
05-23 National Taffy Day
05-24 National Scavenger Hunt Day
05-24 National Escargot Day
05-24 National Brother's Day
05-25 National Tap Dance Day
05-25 Geek Pride Day
05-25 National Missing Children's Day
05-26 National Blueberry Cheesecake Day
05-26 Sally Ride Day
05-26 World Lindy Hop Day
05-26 National Paper Airplane Day
05-27 National Grape Popsicle Day
05-27 National Cellophane Tape Day
05-27 Sunscreen Day
05-28 National Brisket Day
05-28 Amnesty International Day
05-28 World Hunger Day
05-29 National Coq au Vin Day
05-29 Put a Pillow on Your Fridge Day
05-30 National Mint Julep Day
05-30 National Creativity Day
05-30 My Bucket List Day
05-31 National Smile Day
05-31 World No Tobacco Day
05-31 National Macaroon Day
05-31 Web Designer Day
06-01 National Olive Day
06-01 Say Something Nice Day
06-01 Global Day of Parents
06-02 National Rocky Road Day
06-02 National Rotisserie Chicken Day
06-02 National Bubba Day
06-03 National Egg Day
06-03 Repeat Day
06-03 National Chocolate Macaroon Day
06-04 National Cognac Day
06-04 National Cheese Day
06-05 National Gingerbread Day
06-05 National Moonshine Day
06-06 National Yo-Yo Day
06-06 National Gardening Exercise Day
06-06 National Higher Education Day
06-06 Drive-In Movie Day
06-07 National Chocolate Ice Cream Day
06-07 VCR Day
06-07 World Food Safety Day
06-08 National Upsy Daisy Day
06-09 Donald Duck Day
06-09 National Strawberry Rhubarb Pie Day
06-10 National Black Cow Day
06-10 National Herbs and Spices Day
06-10 Ballpoint Pen Day
06-11 National Corn on the Cob Day
06-11 National German Chocolate Cake Day
06-11 King Kamehameha Day
06-12 National Red Rose Day
06-12 National Peanut Butter Cookie Day
06-12 Loving Day
06-12 Superman Day
06-13 National Sewing Machine Day
06-13 National Kitchen Klutzes of America Day
06-13 Weed Your Garden Day
06-14 Flag Day
06-14 National Bourbon Day
06-14 National Strawberry Shortcake Day
06-15 Nature Photography Day
06-15 National Smile Power Day
06-15 Global Wind Day
06-15 National Lobster Day
06-16 National Fudge Day
06-16 Bloomsday
06-17 National Eat Your Vegetables Day
06-17 National Apple Strudel Day
06-17 Global Garbage Man Day
06-18 National Go Fishing Day
06-18 International Panic Day
06-18 Autistic Pride Day
06-19 National Martini Day
06-19 World Sauntering Day
06-19 National Garfield Day
06-20 National Ice Cream Soda Day
06-20 National Vanilla Milkshake Day
06-21 National Peaches and Cream Day
06-21 Go Skateboarding Day
06-22 National Onion Rings Day
06-22 National Chocolate Éclair Day
06-22 National Kissing Day
06-23 National Pink Day
06-23 National Hydration Day
06-23 Typewriter Day
06-23 Olympic Day
06-24 National Pralines Day
06-24 Swim a Lap Day
06-24 International Fairy Day
06-25 National Catfish Day
06-25 Global Beatles Day
06-25 National Strawberry Parfait Day
06-25 Day of the Seafarer
06-26 National Canoe Day
06-26 National Chocolate Pudding Day
06-26 Beautician's Day
06-26 World Refrigeration Day
06-27 National Orange Blossom Day
06-27 Helen Keller Day
06-27 National HIV Testing Day
06-28 National Insurance Awareness Day
06-28 National Tapioca Day
06-28 Tau Day
06-29 National Camera Day
06-29 National Almond Buttercrunch Day
06-29 International Mud Day
06-29 National Waffle Iron Day
06-30 National Meteor Watch Day
""",
    """
07-01 National Gingersnap Day
07-01 International Joke Day
07-01 National Postal Worker Day
07-01 Creative Ice Cream Flavors Day
07-02 National Anisette Day
07-02 I Forgot Day
07-03 National Fried Clam Day
07-03 National Eat Beans Day
07-03 Stay Out of the Sun Day
07-03 International Plastic Bag Free Day
07-04 National Barbecued Spareribs Day
07-04 Sidewalk Egg Frying Day
07-05 National Bikini Day
07-05 National Graham Cracker Day
07-05 National Apple Turnover Day
07-05 National Workaholics Day
07-06 National Fried Chicken Day
07-06 Take Your Webmaster to Lunch Day
07-07 National Strawberry Sundae Day
07-07 Tell the Truth Day
07-07 National Macaroni Day
07-08 National Chocolate with Almonds Day
07-08 National Freezer Pop Day
07-08 National Video Game Day
07-09 National Sugar Cookie Day
07-10 National Kitten Day
07-10 National Piña Colada Day
07-10 Don't Step on a Bee Day
07-10 Nikola Tesla Day
07-11 National Blueberry Muffin Day
07-11 National Mojito Day
07-11 Free Slurpee Day
07-11 Cheer Up the Lonely Day
07-12 National Simplicity Day
07-12 National Pecan Pie Day
07-12 Different Colored Eyes Day
07-12 Malala Day
07-13 National Beans 'n' Franks Day
07-13 Embrace Your Geekness Day
07-14 National Mac and Cheese Day
07-14 Pandemonium Day
07-15 National Give Something Away Day
07-15 National Gummi Worm Day
07-15 National I Love Horses Day
07-15 National Tapioca Pudding Day
07-16 National Corn Fritters Day
07-16 National Fresh Spinach Day
07-17 National Peach Ice Cream Day
07-17 Yellow Pig Day
07-17 World Day for International Justice
07-18 National Caviar Day
07-18 National Sour Candy Day
07-19 National Daiquiri Day
07-19 National Raspberry Cake Day
07-20 Space Exploration Day
07-20 World Jump Day
07-21 Legal Drinking Age Day
07-22 National Hammock Day
07-22 Pi Approximation Day
07-22 National Penuche Fudge Day
07-22 Spoonerism Day
07-23 National Vanilla Ice Cream Day
07-23 Gorgeous Grandma Day
07-24 National Cousins Day
07-24 National Drive-Thru Day
07-24 International Self-Care Day
07-25 National Hot Fudge Sundae Day
07-25 National Merry-Go-Round Day
07-25 National Wine and Cheese Day
07-25 Thread the Needle Day
07-26 National Coffee Milkshake Day
07-26 All or Nothing Day
07-26 Disability Independence Day
07-27 National Crème Brûlée Day
07-27 National Scotch Day
07-27 Take Your Houseplants for a Walk Day
07-27 Walk on Stilts Day
07-28 National Milk Chocolate Day
07-28 World Hepatitis Day
07-28 World Nature Conservation Day
07-29 National Lasagna Day
07-29 National Chicken Wing Day
07-29 Rain Day
07-29 National Lipstick Day
07-30 National Whistleblower Day
07-30 Paperback Book Day
07-30 National Father-in-Law Day
07-31 National Avocado Day
07-31 National Mutt Day
07-31 Harry Potter's Birthday
07-31 Uncommon Instrument Awareness Day
08-01 National Raspberry Cream Pie Day
08-01 Respect for Parents Day
08-01 Spider-Man Day
08-02 National Coloring Book Day
08-03 National Grab Some Nuts Day
08-03 National White Wine Day
08-04 Coast Guard Day
08-05 National Oyster Day
08-05 Work Like a Dog Day
08-06 National Root Beer Float Day
08-06 National Fresh Breath Day
08-06 Hiroshima Day
08-06 National Wiggle Your Toes Day
08-07 National Lighthouse Day
08-07 Purple Heart Day
08-07 National Raspberries 'n Cream Day
08-08 National Frozen Custard Day
08-08 National Sneak Some Zucchini onto Your Neighbor's Porch Day
08-09 National Rice Pudding Day
08-09 Veep Day
08-10 National S'mores Day
08-10 National Spoil Your Dog Day
08-10 National Duran Duran Appreciation Day
08-11 National Son and Daughter Day
08-11 Presidential Joke Day
08-11 Play in the Sand Day
08-12 National Vinyl Record Day
08-12 International Youth Day
08-12 Middle Child's Day
08-13 National Prosecco Day
08-13 National Filet Mignon Day
08-14 National Creamsicle Day
08-14 National Financial Awareness Day
08-14 V-J Day
08-15 National Lemon Meringue Pie Day
08-15 National Check the Chip Day
08-16 National Tell a Joke Day
08-16 National Rum Day
08-17 National Thrift Shop Day
08-17 National I Love My Feet Day
08-17 National Vanilla Custard Day
08-18 National Fajita Day
08-18 Bad Poetry Day
08-18 National Ice Cream Pie Day
08-18 Serendipity Day
08-19 National Aviation Day
08-19 National Potato Day
08-19 World Humanitarian Day
08-19 National Soft Ice Cream Day
08-20 National Radio Day
08-20 National Lemonade Day
08-20 National Bacon Lovers Day
08-21 National Senior Citizens Day
08-21 National Spumoni Day
08-21 Poet's Day
08-22 National Eat a Peach Day
08-22 National Be an Angel Day
08-23 National Sponge Cake Day
08-23 Ride the Wind Day
08-24 National Waffle Day
08-24 National Peach Pie Day
08-24 Pluto Demoted Day
08-24 Vesuvius Day
08-25 National Banana Split Day
08-25 National Kiss and Make Up Day
08-25 National Secondhand Wardrobe Day
08-26 National Cherry Popsicle Day
08-26 National Toilet Paper Day
08-27 National Just Because Day
08-27 National Banana Lovers Day
08-27 National Pots de Crème Day
08-28 National Bow Tie Day
08-28 National Red Wine Day
08-28 National Cherry Turnovers Day
08-29 National Chop Suey Day
08-29 National Lemon Juice Day
08-29 According to Hoyle Day
08-29 More Herbs, Less Salt Day
08-30 National Toasted Marshmallow Day
08-30 National Grief Awareness Day
08-30 Frankenstein Day
08-31 National Trail Mix Day
08-31 National Eat Outside Day
08-31 International Overdose Awareness Day
08-31 National Matchmaker Day
09-01 National Cherry Popover Day
09-01 Emma M. Nutt Day
09-01 National No Rhyme (Nor Reason) Day
09-02 National Blueberry Popsicle Day
09-02 World Coconut Day
09-02 National Calendar Adjustment Day
09-03 National Welsh Rarebit Day
09-03 Skyscraper Day
09-04 National Macadamia Nut Day
09-04 National Newspaper Carrier Day
09-04 Eat an Extra Dessert Day
09-05 National Be Late for Something Day
09-06 National Coffee Ice Cream Day
09-06 Fight Procrastination Day
09-07 National Salami Day
09-07 National Beer Lover's Day
09-07 National Acorn Squash Day
09-07 Neither Snow nor Rain Day
09-08 National Ampersand Day
09-08 National Hug Your Hound Day
09-08 Star Trek Day
09-08 Pardon Day
09-09 National Teddy Bear Day
09-09 National Wiener Schnitzel Day
09-09 Wonderful Weirdos Day
09-10 National TV Dinner Day
09-10 National Swap Ideas Day
09-11 Patriot Day
09-11 National Hot Cross Bun Day
09-11 National Make Your Bed Day
09-12 National Chocolate Milkshake Day
09-12 National Day of Encouragement
09-13 Roald Dahl Day
09-13 National Peanut Day
09-13 Fortune Cookie Day
09-13 Uncle Sam Day
09-14 National Cream-Filled Donut Day
09-14 National Live Creative Day
09-14 Eat a Hoagie Day
09-15 National Linguine Day
09-15 National Online Learning Day
09-15 International Day of Democracy
09-15 Greenpeace Day
09-16 National Play-Doh Day
09-16 Mexican Independence Day
09-16 Collect Rocks Day
09-17 Constitution Day
09-17 National Apple Dumpling Day
09-17 National Monte Cristo Day
09-18 World Water Monitoring Day
09-18 International Read an eBook Day
09-19 National Butterscotch Pudding Day
09-20 National Pepperoni Pizza Day
09-20 National Punch Day
09-21 World Gratitude Day
09-21 Miniature Golf Day
09-21 World Alzheimer's Day
09-22 National White Chocolate Day
09-22 Elephant Appreciation Day
09-22 World Car-Free Day
09-22 American Business Women's Day
09-23 National Checkers Day
09-23 Restless Legs Awareness Day
09-23 National Great American Pot Pie Day
09-23 International Day of Sign Languages
09-24 National Punctuation Day
09-24 National Cherries Jubilee Day
09-25 National Daughters Day
09-25 National Cooking Day
09-25 World Dream Day
09-26 National Pancake Day
09-26 European Day of Languages
09-26 Lumberjack Day
09-27 National Chocolate Milk Day
09-27 Ancestor Appreciation Day
09-27 Crush a Can Day
09-27 Google's Birthday
09-28 National Drink Beer Day
09-28 National Good Neighbor Day
09-28 World Rabies Day
09-29 National Biscotti Day
09-29 Confucius Day
09-29 VFW Day
09-30 National Chewing Gum Day
09-30 International Podcast Day
09-30 International Translation Day
09-30 National Hot Mulled Cider Day
""",
    """
10-01 National Homemade Cookies Day
10-01 International Day of Older Persons
10-02 National Name Your Car Day
10-02 World Farm Animals Day
10-02 Guardian Angels Day
10-03 National Boyfriend Day
10-03 National Caramel Custard Day
10-04 National Vodka Day
10-04 Ten-Four Day
10-04 National Golf Day
10-05 National Apple Betty Day
10-05 Do Something Nice Day
10-06 National Mad Hatter Day
10-06 German-American Day
10-07 National Frappé Day
10-07 National LED Light Day
10-08 National Fluffernutter Day
10-08 American Touch Tag Day
10-08 National Pierogi Day
10-09 National Moldy Cheese Day
10-09 Leif Erikson Day
10-09 Curious Events Day
10-10 National Angel Food Cake Day
10-10 National Cake Decorating Day
10-10 National Handbag Day
10-10 Double Tenth Day
10-11 National Sausage Pizza Day
10-11 It's My Party Day
10-11 Southern Food Heritage Day
10-12 National Savings Day
10-12 Freethought Day
10-12 Old Farmer's Day
10-13 National No Bra Day
10-13 International Skeptics Day
10-13 National M&M Day
10-14 National Chocolate-Covered Insects Day
10-14 Be Bald and Be Free Day
10-15 National Grouch Day
10-15 National Cheese Curd Day
10-15 Global Handwashing Day
10-15 White Cane Safety Day
10-16 Dictionary Day
10-16 National Liqueur Day
10-16 World Spine Day
10-17 National Pasta Day
10-17 Black Poetry Day
10-17 Wear Something Gaudy Day
10-18 National Chocolate Cupcake Day
10-18 Alaska Day
10-18 Persons Day
10-18 No Beard Day
10-19 National Seafood Bisque Day
10-19 Evaluate Your Life Day
10-19 International Gin and Tonic Day
10-20 National Brandied Fruit Day
10-20 International Chefs Day
10-21 National Pumpkin Cheesecake Day
10-21 Count Your Buttons Day
10-21 National Apple Day
10-21 Reptile Awareness Day
10-22 National Nut Day
10-22 International Stuttering Awareness Day
10-22 National Color Day
10-22 International Caps Lock Day
10-23 National Boston Cream Pie Day
10-23 iPod Day
10-23 TV Talk Show Host Day
10-24 National Bologna Day
10-24 National Food Day
10-25 National Greasy Foods Day
10-25 Sourest Day
10-26 National Mule Day
10-26 Day of the Deployed
10-27 National Black Cat Day
10-27 National American Beer Day
10-27 Navy Day
10-27 Cranky Co-Workers Day
10-28 National First Responders Day
10-28 Plush Animal Lover's Day
10-28 National Wild Foods Day
10-28 Statue of Liberty Day
10-29 National Oatmeal Day
10-29 Internet Day
10-29 National Hermit Day
10-30 Checklist Day
10-30 Haunted Refrigerator Night
10-31 National Caramel Apple Day
10-31 Girl Scout Founders Day
10-31 National Magic Day
11-01 National Author's Day
11-01 National Calzone Day
11-01 All Saints' Day
11-01 Cook for Your Pets Day
11-02 National Deviled Egg Day
11-02 Look for Circles Day
11-02 All Souls' Day
11-03 National Housewife's Day
11-03 Cliché Day
11-04 National Candy Day
11-04 King Tut Day
11-04 Use Your Common Sense Day
11-05 National Love Your Red Hair Day
11-05 American Football Day
11-06 Marooned Without a Compass Day
11-06 National Saxophone Day
11-07 National Bittersweet Chocolate with Almonds Day
11-07 National Hug a Bear Day
11-07 Notary Public Day
11-08 National Harvey Wallbanger Day
11-08 X-Ray Day
11-08 Abet and Aid Punsters Day
11-09 National Scrapple Day
11-09 Go to an Art Museum Day
11-09 World Freedom Day
11-09 Chaos Never Dies Day
11-10 National Vanilla Cupcake Day
11-10 Sesame Street Day
11-10 Area Code Day
11-10 Forget-Me-Not Day
11-11 Origami Day
11-11 National Sundae Day
11-11 Singles' Day
11-12 National Happy Hour Day
11-12 National Pizza with the Works Except Anchovies Day
11-12 Chicken Soup for the Soul Day
11-13 National Indian Pudding Day
11-13 Sadie Hawkins Day
11-14 National Pickle Day
11-14 Loosen Up, Lighten Up Day
11-14 National Spicy Guacamole Day
11-15 America Recycles Day
11-15 National Philanthropy Day
11-15 I Love to Write Day
11-16 National Fast Food Day
11-16 Button Day
11-17 National Homemade Bread Day
11-17 World Prematurity Day
11-17 National Unfriend Day
11-17 International Students' Day
11-18 National Vichyssoise Day
11-18 Mickey Mouse's Birthday
11-18 Push-Button Phone Day
11-18 National Apple Cider Day
11-19 National Play Monopoly Day
11-19 World Toilet Day
11-19 Have a Bad Day Day
11-20 National Absurdity Day
11-20 National Peanut Butter Fudge Day
11-20 Universal Children's Day
11-20 Name Your PC Day
11-21 World Television Day
11-21 False Confession Day
11-21 National Stuffing Day
11-22 National Cranberry Relish Day
11-22 Start Your Own Country Day
11-22 Go for a Ride Day
11-23 National Cashew Day
11-23 National Espresso Day
11-23 Doctor Who Day
11-23 Eat a Cranberry Day
11-24 National Sardines Day
11-24 Evolution Day
11-24 Celebrate Your Unique Talent Day
11-24 D.B. Cooper Day
11-25 National Parfait Day
11-25 Shopping Reminder Day
11-25 International Day for the Elimination of Violence Against Women
11-27 National Bavarian Cream Pie Day
11-27 Pins and Needles Day
11-27 Turtle Adoption Day
11-28 Red Planet Day
11-29 National Square Dance Day
11-29 National Chocolates Day
11-29 Electronic Greetings Day
11-30 National Mason Jar Day
11-30 Computer Security Day
11-30 Stay Home Because You're Well Day
11-30 National Mousse Day
11-30 St. Andrew's Day
12-01 National Eat a Red Apple Day
12-01 Day With(out) Art
12-02 National Fritters Day
12-02 Special Education Day
12-02 International Day for the Abolition of Slavery
12-03 National Roof Over Your Head Day
12-03 Make a Gift Day
12-03 National Peppermint Latte Day
12-04 National Sock Day
12-04 Wear Brown Shoes Day
12-04 Santa's List Day
12-05 National Sacher Torte Day
12-05 Bathtub Party Day
12-05 International Ninja Day
12-05 World Soil Day
12-06 St. Nicholas Day
12-06 National Microwave Oven Day
12-06 National Gazpacho Day
12-06 Mitten Tree Day
12-07 National Cotton Candy Day
12-07 International Civil Aviation Day
12-07 Letter Writing Day
12-07 Pearl Harbor Remembrance Day
12-08 National Christmas Tree Day
12-09 National Pastry Day
12-09 International Anti-Corruption Day
12-09 Christmas Card Day
12-10 Dewey Decimal System Day
12-10 National Lager Day
12-10 Nobel Prize Day
12-11 National App Day
12-12 National Poinsettia Day
12-12 National Gingerbread House Day
12-12 National Ambrosia Day
12-13 National Violin Day
12-13 National Cocoa Day
12-14 National Bouillabaisse Day
12-14 Roast Chestnuts Day
12-15 Bill of Rights Day
12-15 Cat Herders Day
12-16 National Chocolate Covered Anything Day
12-16 Beethoven Day
12-17 Wright Brothers Day
12-17 National Maple Syrup Day
12-17 Underdog Day
12-18 National Twin Day
12-18 International Migrants Day
12-19 National Hard Candy Day
12-19 National Oatmeal Muffin Day
12-20 National Sangria Day
12-20 Go Caroling Day
12-20 Games Day
12-20 International Human Solidarity Day
12-21 Crossword Puzzle Day
12-21 National Short Story Day
12-21 Look on the Bright Side Day
12-22 National Cookie Exchange Day
12-22 National Date Nut Bread Day
12-23 National Pfeffernüsse Day
12-23 Roots Day
12-24 National Eggnog Day
12-25 National Pumpkin Pie Day
12-25 A'phabet Day
12-26 National Candy Cane Day
12-26 National Whiner's Day
12-26 National Thank-You Note Day
12-27 National Fruitcake Day
12-27 National Make Cut-Out Snowflakes Day
12-27 Visit the Zoo Day
12-28 National Short Film Day
12-28 Pledge of Allegiance Day
12-29 National Pepper Pot Day
12-29 Tick Tock Day
12-30 National Bacon Day
12-30 National Bicarbonate of Soda Day
12-31 Make Up Your Mind Day
12-31 Unlucky Day
"""
)
