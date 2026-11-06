package com.tingben.app.core;
/** Reflective test adapter exposing Ann-shaped fields without depending on the production type at compile time. */
public class Annonly {
    public final String text;
    public final String speaker;
    public final String instruct;
    public Annonly(String text) { this(text, "旁白", "平稳、清晰地叙述，句间自然停顿"); }
    public Annonly(String text, String speaker, String instruct) {
        this.text = text; this.speaker = speaker; this.instruct = instruct;
    }
}
