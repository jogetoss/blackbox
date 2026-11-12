{
    // The "_settingsDefaults" custom field on BlackBox's Configure page (Manage Plugins). Joget's property editor
    // eval()s this file as one object literal and mixes it into the field.
    //
    // Joget 8.2 shows a field's default ("value" in blackboxPlugin.json) only when the plugin has no saved settings
    // at all, so a setting added after the last save shows empty. This field shows the default instead, and an
    // empty permission picker shows its default plugin (empty means the same thing). A list saved comma-separated
    // (the secret and column name lists) shows one per line; it means the same. It also adds "Reset to
    // defaults", which fills the whole form with the defaults. Nothing is saved until the admin clicks Submit.

    getData: function() {
        return {}; // not a setting
    },

    validate: function() {},

    // Nothing to show: hides its own row. The Reset to defaults button is the page button ("buttons" in
    // blackboxPlugin.json) beside Submit, which calls blackboxResetDefaults().
    renderField: function() {
        return '<style>.property-editor-property[property-name="_settingsDefaults"]{display:none}</style>';
    },

    initScripting: function() {
        var field = this;
        var element = $(field.editor).parent();
        var editor = element.data('editor');
        if (!editor) {
            return; // not the plugin config page
        }
        window.blackboxResetDefaults = function() {
            field.bbRebuild(element, editor, null); // no saved values: the editor uses every default
        };
        $(field.editor).closest('body').find('input.page-button-custom[data-action="resetDefaults"]')
            .attr('title', 'Fills the form with the default settings. Nothing is saved until you click Submit.');
        var saved = editor.options.propertyValues;
        if (!saved || element.data('bbDefaultsFilled')) {
            return; // never saved (the editor already shows the defaults), or filled once already
        }
        element.data('bbDefaultsFilled', true);
        field.bbDefinition(function(definition) {
            var values = $.extend({}, saved);
            var changed = false;
            $.each(definition, function(i, page) {
                $.each(page.properties || [], function(j, p) {
                    if (!p.name || p.value === undefined) {
                        return;
                    }
                    var v = values[p.name];
                    if (p.type === 'textarea' && typeof v === 'string' && v.indexOf(',') >= 0 && !/[\r\n]/.test(v)) {
                        values[p.name] = $.map(v.split(','), function(w) { // saved comma-separated: one per line
                            return $.trim(w) || null;
                        }).join('\n');
                        changed = true;
                        return;
                    }
                    var emptyPicker = p.type === 'elementselect' && v && typeof v === 'object' && !v.className;
                    if (v === undefined || emptyPicker) {
                        values[p.name] = p.value;
                        changed = true;
                    }
                });
            });
            if (changed) {
                field.bbRebuild(element, editor, values, definition);
            }
        });
    },

    // A fresh copy of the settings definition (the editor's copy carries its rendered field objects).
    bbDefinition: function(done) {
        $.ajax({
            url: this.options.contextPath + '/web/property/json/getPropertyOptions',
            data: {value: 'org.joget.marketplace.blackbox.BlackBoxPlugin'},
            dataType: 'json',
            success: done
        });
    },

    bbRebuild: function(element, editor, values, definition) {
        var field = this;
        var render = function(def) {
            var options = $.extend({}, editor.options, {propertiesDefinition: def, propertyValues: values});
            element.data('bbDefaultsFilled', true);
            element.removeData('editor').empty();
            element.propertyEditor(options);
        };
        if (definition) {
            render(definition);
        } else {
            field.bbDefinition(render);
        }
    }
}
