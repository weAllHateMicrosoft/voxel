package com.leaf.game.core;

public final class ConstellationData {

    public final String name;
    public final float  centerRa;
    public final float  centerDec;
    public final float[] segs;

    public ConstellationData(String name, float cRa, float cDec, float... segs) {
        this.name = name; this.centerRa = cRa; this.centerDec = cDec; this.segs = segs;
    }

    public static final ConstellationData[] ALL = {
            new ConstellationData("Orion", 83f, 5f,
                    // Belt
                    81.28f, -0.30f,  84.05f, -1.20f,
                    84.05f, -1.20f,  85.19f, -1.94f,
                    // Shoulders & Head
                    88.79f, 7.41f,   81.28f, -0.30f,   // Betelgeuse → Mintaka
                    81.28f,  6.35f,  84.05f, -1.20f,   // Bellatrix  → Alnilam
                    88.79f, 7.41f,   81.28f,  6.35f,   // Betelgeuse → Bellatrix
                    81.28f,  6.35f,  83.85f,  9.93f,   // Bellatrix → Meissa (head)
                    88.79f,  7.41f,  83.85f,  9.93f,   // Betelgeuse → Meissa
                    // Feet & Sword
                    85.19f, -1.94f,  86.94f, -9.67f,   // Alnitak → Saiph
                    81.28f, -0.30f,  78.63f, -8.20f,   // Mintaka → Rigel
                    84.05f, -1.20f,  83.82f, -5.39f    // Alnilam → Sword
            ),

            new ConstellationData("Ursa Major", 175f, 55f,
                    // Big Dipper Bowl + Handle
                    165.93f, 61.75f,  165.46f, 56.38f,  // Dubhe → Merak
                    165.46f, 56.38f,  178.46f, 53.69f,  // Merak → Phecda
                    178.46f, 53.69f,  183.86f, 57.03f,  // Phecda → Megrez
                    183.86f, 57.03f,  165.93f, 61.75f,  // Megrez → Dubhe
                    183.86f, 57.03f,  193.51f, 55.96f,  // Megrez → Alioth
                    193.51f, 55.96f,  200.98f, 54.93f,  // Alioth → Mizar
                    200.98f, 54.93f,  206.88f, 49.31f,  // Mizar → Alkaid
                    // Head and Front Leg
                    165.93f, 61.75f,  126.80f, 60.70f,  // Dubhe → Muscida
                    165.46f, 56.38f,  135.50f, 41.80f,  // Merak → Tania Borealis
                    135.50f, 41.80f,  134.80f, 39.00f,  // Tania Borealis → Talitha
                    // Back Leg
                    178.46f, 53.69f,  171.30f, 34.00f,  // Phecda → Alula Borealis
                    171.30f, 34.00f,  170.40f, 33.10f   // Alula Borealis → Alula Australis
            ),

            new ConstellationData("Cassiopeia", 15f, 60f,
                    2.30f, 59.15f,  10.12f, 56.54f,
                    10.12f, 56.54f,  14.18f, 60.72f,
                    14.18f, 60.72f,  21.46f, 60.24f,
                    21.46f, 60.24f,  28.60f, 63.67f
            ),

            new ConstellationData("Scorpius", 255f, -25f,
                    // Claws
                    247.35f,-26.43f,  240.51f,-19.81f,  // Antares → Graffias
                    240.51f,-19.81f,  240.00f,-22.60f,  // Graffias → Dschubba
                    240.00f,-22.60f,  247.35f,-26.43f,  // Dschubba → Antares
                    // Tail
                    247.35f,-26.43f,  248.97f,-28.22f,
                    248.97f,-28.22f,  253.08f,-37.10f,
                    253.08f,-37.10f,  255.98f,-34.29f,
                    255.98f,-34.29f,  260.92f,-39.03f,
                    260.92f,-39.03f,  264.33f,-37.30f,
                    264.33f,-37.30f,  263.40f,-37.10f   // Stinger (Shaula)
            ),

            new ConstellationData("Cygnus", 305f, 42f,
                    // Cross
                    310.36f, 45.28f,  305.56f, 40.26f,  // Deneb → Sadr
                    305.56f, 40.26f,  292.68f, 27.96f,  // Sadr → Albireo
                    // Wing 1
                    305.56f, 40.26f,  311.55f, 33.97f,  // Sadr → Gienah
                    311.55f, 33.97f,  318.20f, 30.20f,  // Gienah → Zeta Cyg
                    // Wing 2
                    305.56f, 40.26f,  296.24f, 45.13f,  // Sadr → Delta Cyg
                    296.24f, 45.13f,  292.30f, 51.70f   // Delta Cyg → Iota Cyg
            ),

            new ConstellationData("Gemini", 113f, 25f,
                    // Twins Bodies
                    113.65f, 31.89f,  116.33f, 28.03f,  // Castor → Pollux
                    113.65f, 31.89f,   99.43f, 16.40f,  // Castor → Alhena
                    116.33f, 28.03f,  100.98f, 25.13f,  // Pollux → Mebsuda
                    100.98f, 25.13f,  100.00f, 20.00f   // Mebsuda → Wasat
            ),

            new ConstellationData("Taurus", 67f, 18f,
                    // V-Face
                    68.98f, 16.51f,  67.16f, 19.18f,    // Aldebaran → Ain
                    67.16f, 19.18f,  64.90f, 15.60f,    // Ain → Gamma Tau
                    64.90f, 15.60f,  68.98f, 16.51f,    // Gamma Tau → Aldebaran
                    // Horns
                    68.98f, 16.51f,  81.57f, 28.61f,    // Aldebaran → Elnath
                    67.16f, 19.18f,  84.41f, 21.14f,    // Ain → Zeta Tau
                    // Body to Pleiades
                    64.90f, 15.60f,  56.75f, 24.12f     // Gamma Tau → Alcyone
            )
    };
}