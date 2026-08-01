"""inspect: archives, descriptors, and the merged suite-property map."""

import pytest


def test_inspect_jar(kemu, fixtures):
    result = kemu.ok("inspect", fixtures["COMMAND_FIXTURE_JAR"], command="inspect")
    assert result["displayName"] == "Command Fixture"
    assert result["sourceKind"] == "jar"


def test_inspect_dash_prefixed_paths(kemu, fixtures):
    result = kemu.ok("inspect", fixtures["DASH_PREFIXED_JAR"])
    assert result["displayName"] == "Command Fixture"


@pytest.mark.parametrize(
    ("env_key", "display_name", "midlets"),
    [
        ("GOOD_JAD", "Descriptor Fixture", 1),
        ("ENCODED_SPACE_JAD", "Encoded Descriptor Fixture", 1),
        ("PARENT_RELATIVE_JAD", "Parent Relative Fixture", 1),
        ("PARENT_RELATIVE_ENCODED_JAD", "Parent Relative Encoded Fixture", 1),
        ("MULTI_MIDLET_JAR", "Multi Midlet Fixture", 2),
        ("MEGA_MULTI_MIDLET_JAR", "Mega Multi Midlet Fixture", 2),
        ("MISSING_CLASS_JAR", "Missing Class Fixture", 1),
    ],
)
def test_inspect_descriptor_variants(kemu, fixtures, env_key, display_name, midlets):
    result = kemu.ok("inspect", fixtures[env_key])
    assert result["displayName"] == display_name
    assert len(result["midlets"]) == midlets


@pytest.mark.parametrize(
    ("env_key", "code"),
    [
        ("PLAIN_TEXT_JAR", "UNSUPPORTED_INPUT"),
        ("EMPTY_JAR", "UNSUPPORTED_INPUT"),
        ("NO_MANIFEST_JAR", "UNSUPPORTED_INPUT"),
        ("BAD_JAD", "PATH_NOT_FOUND"),
    ],
)
def test_inspect_invalid_inputs(kemu, fixtures, env_key, code):
    kemu.err("inspect", fixtures[env_key], code=code)


def test_inspect_missing_path(kemu, workdir):
    kemu.err("inspect", str(workdir / "missing.jar"), code="PATH_NOT_FOUND")


@pytest.mark.parametrize(
    ("env_key", "expected_title"),
    [
        ("PROPS_MANIFEST_JAR", "MANIFEST ONLY TITLE"),
        ("PROPS_JAD_ONLY_JAD", "JAD ONLY TITLE"),
        ("PROPS_MANIFEST_FALLBACK_JAD", "MANIFEST ONLY TITLE"),
        ("PROPS_OVERRIDE_JAD", "JAD OVERRIDE TITLE"),
        ("PROPS_MULTI_MIDLET_JAD", "MULTI JAD TITLE"),
    ],
)
def test_suite_property_merge(kemu, fixtures, env_key, expected_title):
    result = kemu.ok("inspect", fixtures[env_key])
    assert result["suiteProperties"]["Fixture-Menu-Title"] == expected_title


def test_manifest_only_keys_survive_jad_midlet_list(kemu, fixtures):
    result = kemu.ok("inspect", fixtures["PROPS_JAD_ONLY_JAD"])
    props = result["suiteProperties"]
    assert props["MicroEdition-Profile"] == "MIDP-2.0"
    assert props["MIDlet-Vendor"] == "KEmulator"
    result = kemu.ok("inspect", fixtures["PROPS_MULTI_MIDLET_JAD"])
    assert len(result["midlets"]) == 2  # the JAD list wins over the MANIFEST
