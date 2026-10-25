package ai.opencode.feature.voice

/**
 * All field, phosphor, colour and shape controls live together; adding a style needs only its
 * enum entry and projection in [LivingOrbField.pattern] (iOS `LivingOrbTunables`).
 *
 * iOS backs these with `Tunable`s (Developer Mode › Tuning); the tuning panel is not ported, so
 * each is its iOS default, with its tuning key in the comment. The presets (`call.livingColours`:
 * Subtle Tint (default), Mono, Vivid; `call.livingVariations`: Settings, Polar Bloom, Living
 * Clouds, Silk Ribbons, Wide Petals, Slow Clouds) only change these values in the panel, so the
 * defaults are Subtle Tint with the Settings style.
 */
object LivingOrbTunables {
    /** `call.livingOrb.style` (Style). */
    val style = LivingOrbStyleOverride.settings
    /** `call.livingOrb.size` (Orb Size, 44…84). */
    const val size = 48f
    /** `call.livingOrb.density` (Pattern Density, 3…12). */
    const val density = 7.0
    /** `call.livingOrb.pitch` (Dot Spacing, 2…6). */
    const val pitch = 3.5
    /** `call.livingOrb.dotSize` (Dot Size, 0.5…1.3). */
    const val dotSize = 1.0
    /** `call.livingOrb.brightness` (Brightness, 0.2…1.6). */
    const val brightness = 0.8
    /** `call.livingOrb.contrast` (Pattern Contrast, 0.5…4). */
    const val contrast = 1.5
    /** `call.livingOrb.glow` (Phosphor Glow, 0…0.8). */
    const val glow = 0.22
    /** `call.livingOrb.unlit` (Unlit Dots, 0…1). */
    const val unlit = 0.35
    /** `call.livingOrb.drift` (Idle Drift, 0.05…1.5). */
    const val drift = 0.3
    /** `call.livingOrb.response` (Voice Response, 0.2…4). */
    const val response = 1.8
    /** `call.livingOrb.idleEnergy` (Idle Energy, 0.05…0.8). */
    const val idleEnergy = 0.3
    /** `call.livingOrb.floor` (Field Floor, 0…0.3). */
    const val floor = 0.06
    /** `call.livingOrb.thinkingSpeed` (Thinking Speed, 0.1…3). */
    const val thinkingSpeed = 0.8
    /** `call.livingOrb.thinkingLight` (Thinking Light, 0…1). */
    const val thinkingLight = 0.24
    /** `call.livingOrb.attack` (Voice Attack (s), 0.02…0.5). */
    const val attack = 0.07
    /** `call.livingOrb.release` (Voice Release (s), 0.05…1). */
    const val release = 0.25
    /** `call.livingOrb.fastDecay` (Fast Decay (s), 0.05…0.5). */
    const val fastDecay = 0.15
    /** `call.livingOrb.afterglow` (Afterglow (s), 0.15…1.5). */
    const val afterglow = 0.6
    /** `call.livingOrb.trailMix` (Trail Mix, 0…1). */
    const val trailMix = 0.28
    /** `call.livingOrb.rotationX` (Horizontal Rotation, −2…2). */
    const val rotationX = 0.47
    /** `call.livingOrb.rotationY` (Vertical Rotation, −2…2). */
    const val rotationY = 0.31
    /** `call.livingOrb.voiceOffset` (Voice Separation, 0…6.28). */
    const val voiceOffset = 2.4
    /** `call.livingPolar.folds` (Polar Folds, 1…8). */
    const val polarFolds = 3
    /** `call.livingPolar.depth` (Fold Depth, 0…3). */
    const val polarDepth = 1.1
    /** `call.livingPolar.speed` (Fold Speed, −3…3). */
    const val polarSpeed = 1.4
    /** `call.livingPolar.cross` (Cross Wave, 0…8). */
    const val polarCross = 3.0
    /** `call.livingClouds.warp` (Cloud Warp, 0…4). */
    const val cloudWarp = 1.5
    /** `call.livingClouds.scale` (Cloud Scale, 0.5…8). */
    const val cloudScale = 3.0
    /** `call.livingClouds.aspect` (Cloud Aspect, 0.2…2). */
    const val cloudAspect = 0.7
    /** `call.livingRibbons.slant` (Ribbon Slant, −2…2). */
    const val ribbonSlant = 0.6
    /** `call.livingRibbons.cross` (Ribbon Crossing, −2…2). */
    const val ribbonCross = 0.4
    /** `call.livingRibbons.speed` (Ribbon Speed, −3…3). */
    const val ribbonSpeed = 0.7

    /** `call.livingColour.enabled` (State Colour). */
    const val colours = true
    /** `call.livingColour.mix` (Tint Strength, 0…1). */
    const val colourMix = 0.65
    /** `call.livingColour.fade` (Colour Blend (s), 0.05…1). */
    const val colourFade = 0.25
    /** `call.livingIdle.hue` (Idle Hue). */
    const val idleHue = 0.43
    /** `call.livingIdle.saturation` (Idle Saturation). */
    const val idleSaturation = 0.25
    /** `call.livingIdle.intensity` (Idle Intensity). */
    const val idleIntensity = 0.8
    /** `call.livingListening.hue` (Listening Hue). */
    const val listeningHue = 0.09
    /** `call.livingListening.saturation` (Listening Saturation). */
    const val listeningSaturation = 0.75
    /** `call.livingListening.intensity` (Listening Intensity). */
    const val listeningIntensity = 1.0
    /** `call.livingThinking.hue` (Thinking Hue). */
    const val thinkingHue = 0.76
    /** `call.livingThinking.saturation` (Thinking Saturation). */
    const val thinkingSaturation = 0.6
    /** `call.livingThinking.intensity` (Thinking Intensity). */
    const val thinkingIntensity = 0.9
    /** `call.livingSpeaking.hue` (Agent Hue). */
    const val speakingHue = 0.56
    /** `call.livingSpeaking.saturation` (Agent Saturation). */
    const val speakingSaturation = 0.95
    /** `call.livingSpeaking.intensity` (Agent Intensity). */
    const val speakingIntensity = 1.0
}
