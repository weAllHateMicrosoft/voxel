package com.leaf.game.core;

/**
 * Hardcoded constellation line segments in J2000 equatorial coordinates
 * (RA in DEGREES, Dec in degrees) plus a centre point for identification.
 *
 * Adding the full BSC5 catalogue via bsc5_converter.py will make the stars
 * align exactly with these lines.  With the procedural fill the bright anchor
 * stars still land close enough to be recognisable.
 *
 * Line segment format: flat float[] of [ra1, dec1, ra2, dec2, ...]
 */
public final class ConstellationData {

    public final String name;
    public final float  centerRa;   // degrees, for identification
    public final float  centerDec;
    public final float[] segs;      // [ra1,dec1, ra2,dec2, ...]

    public ConstellationData(String name, float cRa, float cDec, float... segs) {
        this.name = name; this.centerRa = cRa; this.centerDec = cDec; this.segs = segs;
    }

    // ── Catalogue ─────────────────────────────────────────────────────────────
    public static final ConstellationData[] ALL = {

        new ConstellationData("Orion", 83f, 5f,
            // Belt
            81.28f, -0.30f,  84.05f, -1.20f,
            84.05f, -1.20f,  85.19f, -1.94f,
            // Shoulders
            88.79f, 7.41f,   81.28f, -0.30f,   // Betelgeuse → Mintaka
            81.28f,  6.35f,  84.05f, -1.20f,   // Bellatrix  → Alnilam
            88.79f, 7.41f,   81.28f,  6.35f,   // Betelgeuse → Bellatrix
            // Feet
            85.19f, -1.94f,  86.94f, -9.67f,   // Alnitak → Saiph
            81.28f, -0.30f,  78.63f, -8.20f    // Mintaka → Rigel
        ),

        new ConstellationData("Big Dipper", 189f, 56f,
            165.93f, 61.75f,  165.46f, 56.38f,  // Dubhe → Merak
            165.46f, 56.38f,  178.46f, 53.69f,  // Merak → Phecda
            178.46f, 53.69f,  183.86f, 57.03f,  // Phecda → Megrez
            183.86f, 57.03f,  165.93f, 61.75f,  // Megrez → Dubhe (bowl)
            183.86f, 57.03f,  193.51f, 55.96f,  // Megrez → Alioth (handle)
            193.51f, 55.96f,  200.98f, 54.93f,
            200.98f, 54.93f,  206.88f, 49.31f
        ),

        new ConstellationData("Cassiopeia", 15f, 60f,
             2.30f, 59.15f,  10.12f, 56.54f,
            10.12f, 56.54f,  14.18f, 60.72f,
            14.18f, 60.72f,  21.46f, 60.24f,
            21.46f, 60.24f,  28.60f, 63.67f
        ),

        new ConstellationData("Leo", 168f, 18f,
            152.09f, 11.97f,  154.99f, 19.85f,  // Regulus → Algieba (sickle)
            154.99f, 19.85f,  151.83f, 16.76f,  // Algieba → Eta Leo
            151.83f, 16.76f,  148.19f, 26.00f,  // → Mu Leo (sickle tip)
            154.99f, 19.85f,  168.53f, 20.52f,  // Algieba → Zosma (body)
            168.53f, 20.52f,  177.26f, 14.57f   // Zosma → Denebola (tail)
        ),

        new ConstellationData("Scorpius", 255f, -25f,
            240.51f,-19.81f,  247.35f,-26.43f,  // Graffias → Antares
            247.35f,-26.43f,  248.97f,-28.22f,
            248.97f,-28.22f,  253.08f,-37.10f,  // tail
            253.08f,-37.10f,  255.98f,-34.29f,
            255.98f,-34.29f,  260.92f,-39.03f,
            260.92f,-39.03f,  264.33f,-37.30f   // stinger
        ),

        new ConstellationData("Gemini", 113f, 25f,
            113.65f, 31.89f,  116.33f, 28.03f,  // Castor → Pollux (twins' heads)
            113.65f, 31.89f,   99.43f, 16.40f,  // Castor → Alhena (Castor's body)
            116.33f, 28.03f,   99.43f, 16.40f,  // Pollux → Alhena (Pollux's body)
             99.43f, 16.40f,  100.98f, 25.13f   // Alhena → Mebsuda
        ),

        new ConstellationData("Taurus", 67f, 18f,
            68.98f, 16.51f,  81.57f, 28.61f,   // Aldebaran → Elnath (horns)
            68.98f, 16.51f,  84.41f, 21.14f,   // Aldebaran → Zeta Tau
            68.98f, 16.51f,  67.16f, 19.18f,   // Aldebaran → Ain (face of bull)
            56.75f, 24.12f,  67.16f, 19.18f    // Pleiades centre → Ain
        ),

        new ConstellationData("Virgo", 197f, 2f,
            190.42f, -1.45f,  201.30f,-11.16f,  // Porrima → Spica
            195.55f, 10.96f,  190.42f, -1.45f,  // Vindemiatrix → Porrima
            201.30f,-11.16f,  203.68f, -0.60f   // Spica → Heze
        ),

        new ConstellationData("Cygnus", 305f, 42f,
            310.36f, 45.28f,  305.56f, 40.26f,  // Deneb → Sadr (top of cross)
            305.56f, 40.26f,  292.68f, 27.96f,  // Sadr → Albireo (bottom of cross)
            311.55f, 33.97f,  305.56f, 40.26f,  // Gienah → Sadr
            305.56f, 40.26f,  296.24f, 45.13f   // Sadr → Delta Cyg (cross arms)
        ),

        new ConstellationData("Lyra", 281f, 37f,
            279.23f, 38.78f,  282.52f, 33.36f,  // Vega → Sheliak
            279.23f, 38.78f,  283.62f, 36.90f,  // Vega → Delta Lyr
            282.52f, 33.36f,  284.74f, 32.69f,  // Sheliak → Sulafat (small parallelogram)
            284.74f, 32.69f,  283.62f, 36.90f
        ),

        new ConstellationData("Aquila", 295f, 9f,
            296.57f, 10.61f,  297.70f,  8.87f,  // Tarazed → Altair
            297.70f,  8.87f,  298.83f,  6.41f,  // Altair → Alshain
            286.36f, 13.86f,  297.70f,  8.87f   // Zeta Aql → Altair
        ),

        new ConstellationData("Ursa Minor", 233f, 80f,
            222.68f, 74.16f,  230.18f, 71.83f,  // Kochab → Pherkad
            230.18f, 71.83f,  236.02f, 77.79f,  // Pherkad → Zeta UMi
            236.02f, 77.79f,  251.57f, 82.03f,  // Zeta UMi → Eps UMi
            251.57f, 82.03f,  263.05f, 86.58f,  // Eps UMi → Delta UMi
            263.05f, 86.58f,   37.95f, 89.26f   // Delta UMi → Polaris
        ),

        new ConstellationData("Canis Major", 103f, -22f,
             95.67f,-17.95f,  101.29f,-16.72f,  // Mirzam → Sirius
            101.29f,-16.72f,  107.10f,-26.39f,  // Sirius → Wezen
            107.10f,-26.39f,  104.65f,-28.97f,  // Wezen → Adhara
            101.29f,-16.72f,  104.65f,-28.97f   // Sirius → Adhara
        ),

        new ConstellationData("Perseus", 50f, 45f,
            47.04f, 40.96f,   51.08f, 49.86f,  // Algol → Mirfak
            51.08f, 49.86f,   42.81f, 55.90f   // Mirfak → Gorgonea Tertia
        ),

        new ConstellationData("Auriga", 77f, 42f,
            79.17f, 45.99f,   75.62f, 37.22f,  // Capella → Mahasim
            75.62f, 37.22f,   65.73f, 33.17f,  // Mahasim → Menkib (Eps Aur)
            65.73f, 33.17f,   79.17f, 45.99f   // Menkib → Capella
        ),
    };
}
