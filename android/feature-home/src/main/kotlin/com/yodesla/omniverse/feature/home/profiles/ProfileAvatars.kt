package com.yodesla.omniverse.feature.home.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.designsystem.OmniTheme
import kotlin.math.floor

/** Built-in avatar looks: no image assets, just a gradient disc + the name's initial. */
private val AvatarPalettes = listOf(
    Color(0xFF7C4DFF) to Color(0xFF311B92),
    Color(0xFFFF6E40) to Color(0xFFBF360C),
    Color(0xFF26C6DA) to Color(0xFF006064),
    Color(0xFF66BB6A) to Color(0xFF1B5E20),
    Color(0xFFFFD54F) to Color(0xFFE65100),
    Color(0xFFEC407A) to Color(0xFF880E4F),
    Color(0xFF5C6BC0) to Color(0xFF1A237E),
    Color(0xFF9CCC65) to Color(0xFF33691E),
)

/**
 * One built-in pixel-art avatar (task 84b): an original 16x16 character grid plus the palette
 * its characters name ('.' is always transparent). Drawn on a Canvas as integer-pixel rects -
 * no image files, no copyrighted characters. ProfileAvatarsTest validates every grid.
 */
internal class PixelAvatarDef(
    val name: String,
    val bg: Color,
    val palette: Map<Char, Color>,
    val grid: List<String>,
)

private fun pal(vararg entries: Pair<Char, Color>): Map<Char, Color> =
    entries.toMap() + ('.' to Color.Transparent)

/** Pixel avatars occupy avatar indexes 8..23 (gradients keep 0..7, meaning unchanged). */
internal val PixelAvatars: List<PixelAvatarDef> = listOf(
    PixelAvatarDef(
        name = "Cat",
        bg = Color(0xFF3A2410),
        palette = pal(
            'K' to Color(0xFF241A12), 'O' to Color(0xFFE8913C), 'G' to Color(0xFF57D65A),
            'P' to Color(0xFFFF7EB0), 'W' to Color(0xFFFFFFFF), 'D' to Color(0xFFB35A16),
        ),
        grid = listOf(
            "..KK........KK..",
            ".KOOK......KOOK.",
            ".KOOOK....KOOOK.",
            ".KOOOOK..KOOOOK.",
            ".KOOOOOKKOOOOOK.",
            "KOOOOOOOOOOOOOOK",
            "KOOGGOOOOOOGGOOK",
            "KOGKGOOOOOGKGOOK",
            "KOOOOOWPPWOOOOOK",
            "KOOOOOWKKWOOOOOK",
            "KOOOOOOWWOOOOOOK",
            "..KDOOOOOOOODK..",
            "..KOOOOOOOOOOK..",
            "...KKKKKKKKKK...",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Dog",
        bg = Color(0xFF2E2013),
        palette = pal(
            'K' to Color(0xFF241A12), 'B' to Color(0xFFA9714B), 'W' to Color(0xFFF7F0E6),
            'T' to Color(0xFFFF6E9F),
        ),
        grid = listOf(
            "................",
            ".KKKK......KKKK.",
            ".KBBBK....KBBBK.",
            "KBBBBBKKKKBBBBBK",
            "KBBBBBBBBBBBBBBK",
            "KBBKBBBBBBBBKBBK",
            "KBBKBBBBBBBBKBBK",
            "KBBBBWWWWWWBBBBK",
            "KBBBBWKKKKWBBBBK",
            "KBBBBWKBBKWBBBBK",
            "KBBBBWWTTWWBBBBK",
            ".KBBBBTTTTBBBBK.",
            "..KKBBBBBBBBKK..",
            "....KKKKKKKK....",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Fox",
        bg = Color(0xFF331B0E),
        palette = pal(
            'K' to Color(0xFF241A12), 'O' to Color(0xFFE8763C), 'G' to Color(0xFF2A2118),
            'W' to Color(0xFFF7F0E6), 'P' to Color(0xFFFF7EB0),
        ),
        grid = listOf(
            "..K..........K..",
            ".KOK........KOK.",
            ".KOOK......KOOK.",
            ".KOOOK....KOOOK.",
            ".KOOOOK..KOOOOK.",
            ".KOOOOOKKOOOOOK.",
            "KOOOOOOOOOOOOOOK",
            "KOOGGOOOOOOGGOOK",
            "KOGGOOOOOOOGGOOK",
            "KOOOOOWWWWOOOOOK",
            "KOOOOOWPKWOOOOOK",
            ".KOOOOWKKWOOOOK.",
            ".KOOOOOWWOOOOOK.",
            "..KOOOOOOOOOOK..",
            "...KKOOOOOOKK...",
            ".....KKKKKK.....",
        ),
    ),
    PixelAvatarDef(
        name = "Frog",
        bg = Color(0xFF12281A),
        palette = pal(
            'K' to Color(0xFF12281A), 'G' to Color(0xFF5FD05F), 'W' to Color(0xFFFFFFFF),
        ),
        grid = listOf(
            "..KKK......KKK..",
            ".KGGGK....KGGGK.",
            ".KGWWK....KWWGK.",
            ".KGGGK....KGGGK.",
            "KGGGGKKKKKKGGGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGKGGGGGGGGKGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGGKKKKKKKKGGGK",
            "KGGGGGGGGGGGGGGK",
            ".KGGGGGGGGGGGGK.",
            "..KGGGGGGGGGGK..",
            "...KKGGGGGGKK...",
            ".....KKKKKK.....",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Ghost",
        bg = Color(0xFF232333),
        palette = pal(
            'K' to Color(0xFF3A3A4A), 'W' to Color(0xFFF2F6FF),
        ),
        grid = listOf(
            ".....KKKKKK.....",
            "...KKWWWWWWKK...",
            "..KWWWWWWWWWWK..",
            ".KWWWWWWWWWWWWK.",
            ".KWWKKWWWWKKWWK.",
            ".KWWKKWWWWKKWWK.",
            ".KWWWWWWWWWWWWK.",
            ".KWWWWWKKWWWWWK.",
            ".KWWWWWKKWWWWWK.",
            ".KWWWWWWWWWWWWK.",
            ".KWWWWWWWWWWWWK.",
            "KWWWWWWWWWWWWWWK",
            "KWWWWWWWWWWWWWWK",
            "KWWKWWWWWWWWKWWK",
            "KWWKWWWWWWWWKWWK",
            ".KK..KKKKKK..KK.",
        ),
    ),
    PixelAvatarDef(
        name = "Slime",
        bg = Color(0xFF16301C),
        palette = pal(
            'K' to Color(0xFF1E3A1E), 'G' to Color(0xFF7BE06A), 'W' to Color(0xFFFFFFFF),
        ),
        grid = listOf(
            "................",
            "......KKKK......",
            "....KKGGGGKK....",
            "...KGGGGGGGGK...",
            "..KGGGGGGGGGGK..",
            ".KGGGGGGGGGGGGK.",
            ".KGWGGGGGGGWGGK.",
            ".KGKGGGGGGGKGGK.",
            ".KGGGGGGGGGGGGK.",
            "KGGGGGGGGGGGGGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGGGGGGGGGGGGGK",
            "KGGKKGGGGGGKKGGK",
            ".KK..KKKKKK..KK.",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Robot",
        bg = Color(0xFF1A2230),
        palette = pal(
            'K' to Color(0xFF10141C), 'S' to Color(0xFFB9C4D6), 'R' to Color(0xFFFF4757),
            'G' to Color(0xFF57D65A),
        ),
        grid = listOf(
            ".......KK.......",
            ".......KK.......",
            "....KKKKKKKK....",
            "...KSSSSSSSSK...",
            "..KSSSSSSSSSSK..",
            ".KSSRRSSSSRRSSK.",
            ".KSSRRSSSSRRSSK.",
            ".KSSSSSSSSSSSSK.",
            ".KSSSKKKKKKSSSK.",
            ".KSSSSSSSSSSSSK.",
            "..KSSSSSSSSSSK..",
            "..KGGSSSSSSGGK..",
            "..KGGSSSSSSGGK..",
            "...KKSSSSSSKK...",
            "..KKSSKKKKSSKK..",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Alien",
        bg = Color(0xFF14281A),
        palette = pal(
            'K' to Color(0xFF14281A), 'G' to Color(0xFF6FE07A), 'B' to Color(0xFF101820),
        ),
        grid = listOf(
            ".....KKKKKK.....",
            "...KKGGGGGGKK...",
            "..KGGGGGGGGGGK..",
            ".KGGGGGGGGGGGGK.",
            ".KGBBGGGGGGGBBK.",
            ".KGBBGGGGGGGBBK.",
            ".KGGGGGGGGGGGGK.",
            ".KGGGGGKKGGGGGK.",
            "..KGGGGGGGGGGK..",
            "...KGGGGGGGGK...",
            "....KGGGGGGK....",
            "....KGGGGGGK....",
            "..KGK.KGGK.KGK..",
            "...KKK....KKK...",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Astronaut",
        bg = Color(0xFF1B2A44),
        palette = pal(
            'K' to Color(0xFF2A2A33), 'S' to Color(0xFFD8DEE9), 'V' to Color(0xFF3A6EA8),
            'W' to Color(0xFFFFFFFF),
        ),
        grid = listOf(
            "....KKKKKKKK....",
            "..KKSSSSSSSSKK..",
            ".KSSSSSSSSSSSSK.",
            ".KSSVVVVVVVVSSK.",
            ".KSSVVVVVVVVSSK.",
            ".KSSVWWVVWWVSSK.",
            ".KSSVWWVVWWVSSK.",
            ".KSSVVVVVVVVSSK.",
            ".KSSVVVVVVVVSSK.",
            ".KSSVVVVVVVVSSK.",
            ".KSSVVVVVVVVSSK.",
            "..KSSSSSSSSSSK..",
            "...KKSSSSSSKK...",
            "....KKKKKKKK....",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Rocket",
        bg = Color(0xFF141A28),
        palette = pal(
            'K' to Color(0xFF2A2A33), 'W' to Color(0xFFF7F0E6), 'R' to Color(0xFFFF5252),
            'O' to Color(0xFFFFB347), 'Y' to Color(0xFFFFE066),
        ),
        grid = listOf(
            ".......KK.......",
            "......KWWK......",
            ".....KWWWWK.....",
            ".....KWWWWK.....",
            "....KWWRRWWK....",
            "....KWWRRWWK....",
            "....KWWWWWWK....",
            "...KWWWWWWWWK...",
            "..KWWKWWWWKWWK..",
            ".KWWKKKWWKKKWWK.",
            "..OOO.KWWK.OOO..",
            "...OOO.KK.OOO...",
            "...YY.OOOO.YY...",
            ".....YY..YY.....",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Planet",
        bg = Color(0xFF241A44),
        palette = pal(
            'K' to Color(0xFF1A1433), 'P' to Color(0xFFB07CE8), 'D' to Color(0xFF7A4FBF),
            'R' to Color(0xFFFFD54F),
        ),
        grid = listOf(
            "................",
            ".....KKKKKK.....",
            "...KKPPPPPPKK...",
            "..KPPPPPPPPPPK..",
            ".KPPPPPPPPPPPPK.",
            ".KPPDDPPPPDDPPK.",
            ".KPPDDPPPPDDPPK.",
            "RRRRRRRRRRRRRRRR",
            ".KPPPPPPPPPPPPK.",
            ".KPPPPPPPPPPPPK.",
            "..KPPDDPPDDPPK..",
            "..KPPPPPPPPPPK..",
            "...KKPPPPPPKK...",
            "....KKKKKKKK....",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Mushroom",
        bg = Color(0xFF2A1A12),
        palette = pal(
            'K' to Color(0xFF2A1A12), 'R' to Color(0xFFE8484F), 'W' to Color(0xFFF7F0E6),
            'D' to Color(0xFFB33A3F),
        ),
        grid = listOf(
            "................",
            ".....KKKKKK.....",
            "...KKRRRRRRKK...",
            "..KRRWWRRWWRRK..",
            ".KRRWWRRRRWWRRK.",
            ".KRRRRRRRRRRRRK.",
            ".KRRWWRRRRWWRRK.",
            ".KDDDDDDDDDDDDK.",
            "...KWWWWWWWWK...",
            "...KWWWWWWWWK...",
            "...KWWKKKKWWK...",
            "...KWWWWWWWWK...",
            "...KWWWWWWWWK...",
            "..KWWWWWWWWWWK..",
            "..KKKKKKKKKKKK..",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Pizza",
        bg = Color(0xFF33200E),
        palette = pal(
            'K' to Color(0xFF3A2410), 'Y' to Color(0xFFFFD54F), 'R' to Color(0xFFE8484F),
            'G' to Color(0xFF5FA845), 'O' to Color(0xFFD9A05B),
        ),
        grid = listOf(
            "................",
            "................",
            "....KKKKKKKK....",
            "...KOOOOOOOOK...",
            "...KYYYYYYYYK...",
            "..KYYYYRYYYYYK..",
            "..KYYYYYYYYYYK..",
            ".KYYRYYYYYYRYYK.",
            ".KYYYYYYYYYYYYK.",
            "KYYGRYYYYYYRGYYK",
            "KYYYYYYYYYYYYYYK",
            "KYYYYYRRRRYYYYYK",
            "KYYYYYRRRRYYYYYK",
            "KYYYYYYYYYYYYYYK",
            "KKKKKKKKKKKKKKKK",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Controller",
        bg = Color(0xFF181820),
        palette = pal(
            'K' to Color(0xFF181820), 'S' to Color(0xFF3F4657), 'R' to Color(0xFFFF5252),
            'G' to Color(0xFF57D65A), 'Y' to Color(0xFFFFD54F), 'W' to Color(0xFFFFFFFF),
        ),
        grid = listOf(
            "................",
            "................",
            "...KKKKKKKKKK...",
            "..KSSSSSSSSSSK..",
            ".KSSSSSSSSSSSSK.",
            ".KSSSKSSSSSRSSK.",
            ".KSKKKKSSSGSSSK.",
            ".KSSSKSSYSSWSSK.",
            ".KSSSSSSSGSSSSK.",
            ".KSSSSSSSSSSSSK.",
            ".KSSSSSSSSSSSSK.",
            "..KSSKKSSSKSSK..",
            "..KSSKKSSSKSSK..",
            "...KKK....KKK...",
            "................",
            "................",
        ),
    ),
    PixelAvatarDef(
        name = "Cactus",
        bg = Color(0xFF123018),
        palette = pal(
            'K' to Color(0xFF123018), 'G' to Color(0xFF4FBF67), 'P' to Color(0xFFD98243),
            'O' to Color(0xFFB35A16),
        ),
        grid = listOf(
            "................",
            ".......KK.......",
            "......KGGK......",
            "......KGGK......",
            "..KK..KGGK..KK..",
            "..KGGKGGGGKGGK..",
            "..KGGKGGGGKGGK..",
            "..KGGKGGGGKGGK..",
            "..KGGKKGGKKGGK..",
            "..KGGGGGGGGGGK..",
            "...KGGGGGGGGK...",
            "...KGGGGGGGGK...",
            "...KGGGGGGGGK...",
            "....KKGGGGKK....",
            "...OPKKPPKKPO...",
            "...OPPPPPPPPO...",
        ),
    ),
    PixelAvatarDef(
        name = "Star",
        bg = Color(0xFF2E2A10),
        palette = pal(
            'K' to Color(0xFF6B5A10), 'W' to Color(0xFFFFE066),
        ),
        grid = listOf(
            "................",
            ".......KK.......",
            "......KWWK......",
            "......KWWK......",
            "KKKKKKWWWWKKKKKK",
            ".KKKKKWWWWKKKKK.",
            "..KKKKWWWWKKKK..",
            "...KKWWWWWWKK...",
            "....KWWWWWWK....",
            "....KWWWWWWK....",
            "...KWWK..KWWK...",
            "..KWWK....KWWK..",
            ".KWWK......KWWK.",
            "KKKK........KKKK",
            "................",
            "................",
        ),
    ),
)

fun avatarInitial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val count = ProfileRepository.AVATAR_COUNT
    val idx = ((profile.avatar % count) + count) % count
    if (idx < AvatarPalettes.size) {
        val palette = AvatarPalettes[idx]
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(palette.first, palette.second))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                avatarInitial(profile.name),
                style = OmniTheme.type.title.copy(fontSize = (size.value / 2.4f).sp),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    } else {
        PixelAvatarView(PixelAvatars[(idx - AvatarPalettes.size) % PixelAvatars.size], size, modifier)
    }
}

/** Pixel art drawn as integer-pixel rects so it stays crisp at every TV resolution. */
@Composable
private fun PixelAvatarView(def: PixelAvatarDef, avatarSize: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(def.bg.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // DrawScope.size is already in device pixels. Whole-integer cells keep pixel art crisp.
            val cell = (size.width / 16f).toInt().coerceAtLeast(1).toFloat()
            val span = cell * 16f
            val ox = (size.width - span) / 2f
            val oy = (size.height - span) / 2f
            for (r in 0 until 16) {
                val row = def.grid[r]
                for (c in 0 until 16) {
                    val color = def.palette[row[c]] ?: Color.Transparent
                    if (color != Color.Transparent) {
                        drawRect(color, Offset(ox + c * cell, oy + r * cell), Size(cell, cell))
                    }
                }
            }
        }
    }
}

/** Gold ring so the active profile is identifiable at a glance on every screen. */
@Composable
fun ProfileAvatarCurrent(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size + 6.dp).clip(CircleShape).background(OmniTheme.colors.accent)) {
        ProfileAvatar(profile, size, Modifier.align(Alignment.Center))
    }
}
