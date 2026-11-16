{
    getData: function() {
        return {};
    },

    validate: function() {},

    renderField: function() {
        return '<style>.property-editor-property[property-name="_settingsDefaults"]{display:none}</style>';
    },

    initScripting: function() {
        var field = this;
        var element = $(field.editor).parent();
        var editor = element.data('editor');
        if (!editor) {
            return;
        }
        window.blackboxResetDefaults = function() {
            field.bbRebuild(element, editor, null);
        };
        $(field.editor).closest('body').find('input.page-button-custom[data-action="resetDefaults"]')
            .attr('title', 'Fills the form with the default settings. Nothing is saved until you click Submit.');
        var saved = editor.options.propertyValues;
        if (!saved || element.data('bbDefaultsFilled')) {
            return;
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
                        values[p.name] = $.map(v.split(','), function(w) {
                            return $.trim(w) || null;
                        }).join('\n');
                        changed = true;
                        return;
                    }
                    // only Who can upload: empty means its default. The other pickers: empty = nobody, so it stays empty
                    var emptyPicker = p.name === 'canUpload' && p.type === 'elementselect' && v && typeof v === 'object' && !v.className;
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
