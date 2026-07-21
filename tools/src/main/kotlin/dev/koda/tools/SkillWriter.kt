package dev.koda.tools

/**
 * Persists a new skill the agent authored from experience. Where it's written
 * is an adapter concern; the tool supplies a name, description, and body.
 */
interface SkillWriter {
    /** Returns a human-readable result (path created, or an error message). */
    fun create(name: String, description: String, body: String): String
}

class NoopSkillWriter : SkillWriter {
    override fun create(name: String, description: String, body: String) = "Skill creation is unavailable here."
}
