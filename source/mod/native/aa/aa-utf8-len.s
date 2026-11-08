@ Pinned source: Seogaemo v0.1.47 commit 7616e43f669916445f151a7aeb279b32ea09cd4b, AGPL-3.0-or-later.
@ Modified by HMG-Seogaemo (HMG-API upstream recipes aa-tbt-* carry the unfixed helper).
@ UTF-8 length helper of the Android Auto turn-by-turn recipes (aa-tbt-48ee793a,
@ aa-tbt-a0bea1c4): the code that packs a string into the NavigationStatus callbacks.
@   in:  r0 = bytes, r1 = byte count
@   out: r0 = byte count to pack, at most 96, never ending inside a character
@   clobbers r1, r2, r3 (the replaced helper clobbered the same registers)
@ Up to 96 bytes the count is returned unchanged. Longer, the 96-byte boundary may fall
@ inside a character: back off to the start of that character and drop it whole.
@ The old helper always dropped trailing continuation bytes, which cut the last
@ Hangul syllable of every short string. Exactly 32 bytes: it replaces that slot at
@ segment offset 0x17a in both recipes. Check: test/aautf8/test.py.
        .syntax unified
        .thumb
        .text
        .thumb_func
        .globl  utf8_len
utf8_len:
        cmp     r1, #96
        bhi     1f
        mov     r0, r1
        bx      lr
1:      mov     r2, r0
        movs    r0, #96
2:      ldrb    r3, [r2, r0]
        movs    r1, #0xc0
        ands    r3, r1
        cmp     r3, #0x80
        bne     3f
        subs    r0, #1
        bne     2b
3:      bx      lr
        nop
        nop
