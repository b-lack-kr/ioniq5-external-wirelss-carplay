@ Pinned source: Seogaemo v0.1.47 commit 7616e43f669916445f151a7aeb279b32ea09cd4b, AGPL-3.0-or-later.
@ Modified by HMG-Seogaemo (HMG-API upstream recipes aa-tbt-* mark a lane as one value, 1 or 2).
@ Lane packing of the Android Auto turn-by-turn recipes (aa-tbt-48ee793a, aa-tbt-a0bea1c4):
@ the code that packs the lanes of the first NavigationState step into the next-turn event 34.
@ The upstream byte for each lane (r10) is 1, or 2 when any direction is recommended: the cluster
@ needs to know which arrow of the lane is on the route. Now r10 starts at 0 and the byte is the
@ mask of the lane's arrow slots whose direction is recommended (bit k: slot k), so
@ AaTurns/CpLaneFrame can colour the route arrow blue and the others grey.
@   in the arrow store (segment +0x101c, `strb.w r1, [r3, #0x6c]` stores the arrow of slot r5-1):
@   the store moves to the tail of the segment, which sets bit r5-1 of r10 when the direction
@   has a recommended flag, and branches back to +0x1020
@   +0xf7e   `mov.w r10, #1` -> `mov.w r10, #0` (the lane starts with no recommended arrow)
@   +0xff2   `it ne; movne.w r10, #2` (the old lane flag) -> three nops
@   +0x1598  the arrow table entry of shape 9 (U-turn right) 0 -> 5, like shape 8 (U-turn left):
@            both are the cluster's U-turn arrow; shape 9 used to drop the arrow
@ The offsets are in the segment; TAIL (the 4-aligned end of the unpatched segment) is given by
@ the assembler flag -DTAIL=<n>. Check: test/aalanes/test.py.
        .syntax unified
        .thumb
        .text
        .org    0xf7e
        mov.w   r10, #0
        .org    0xff2
        nop
        nop
        nop
        .org    0x101c
        b.w     tail
        .org    0x1020
back:
        .org    0x1598
        .byte   5
        .org    TAIL
tail:
        strb.w  r1, [r3, #0x6c]
        ldr     r1, [sp, #0xcc]
        cbz     r1, 1f
        ldr     r1, [sp, #0xa8]
        cbz     r1, 1f
        subs    r1, r5, #1
        movs    r3, #1
        lsls    r3, r1
        orr.w   r10, r10, r3
1:      b.w     back
