/* NEEV KOR 260902, libCarPlayCoreUtils.so. Assemble this sparse section at VMA 0. */
.syntax unified
.thumb
.section .text
.org 0x4dcce
    bne.w extra_wifi
.org 0x4dcd2
original_wifi:
.org 0x4de84
original_default:

/* Zero padding on the already mapped last RX file page. Only reached after the
   stock strcmp checks for rndis0, ncm0, wlan0 and tether have all failed.
   r4 holds the actual socket interface name. r0 is a scratch register here. */
.org 0x9b2b0
extra_wifi:
.macro match_char offset, value
    ldrb r0, [r4, #\offset]
    cmp r0, #\value
    bne default_type
.endm
    match_char 0, 114  /* r */
    match_char 1, 116  /* t */
    match_char 2, 119  /* w */
    match_char 3, 108  /* l */
    match_char 4, 97   /* a */
    match_char 5, 110  /* n */

.macro require_digit
    subs r0, #48
    cmp r0, #9
    bhi default_type
.endm
    ldrb r0, [r4, #6]
    require_digit
    ldrb r0, [r4, #7]
    cbz r0, wifi
    require_digit
    ldrb r0, [r4, #8]
    cbz r0, wifi
    require_digit
    ldrb r0, [r4, #9]
    cbnz r0, default_type
wifi:
    b.w original_wifi
default_type:
    b.w original_default
